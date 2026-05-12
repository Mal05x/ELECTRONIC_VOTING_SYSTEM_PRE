package com.evoting.service;

import com.evoting.exception.EvotingAuthException;
import com.evoting.model.LivenessResult;
import com.evoting.repository.LivenessResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BiometricService v3.1
 * =====================
 * Adds liveness mode switching: PASSIVE (MiniFASNet/CDCN++) or ACTIVE (MediaPipe challenge).
 *
 * New in v3.1:
 *   - livenessMode field (PASSIVE | ACTIVE) with runtime setter/getter.
 *   - storeActiveLivenessResult() — called by LivenessStreamHandler after
 *     WebSocket challenge completes; allows getLivenessResult() to work
 *     identically regardless of which model evaluated the voter.
 *   - activeServiceUrl for the MediaPipe service (port 5002).
 */
@Service
@Slf4j
public class BiometricService {

    private static final int  RESULT_TTL_MINUTES           = 10;
    private static final int  MIN_FRAME_BYTES              = 1024;
    private static final int  MIN_REAL_FRAME               = 5000;
    private static final int  CIRCUIT_TRIP_THRESHOLD       = 3;
    private static final long CIRCUIT_RECOVERY_INTERVAL_MS = 60_000L;

    // ── Config ─────────────────────────────────────────────────────────────
    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    /** NEW: URL of the MediaPipe active liveness service (port 5002). */
    @Value("${liveness.active-service.url:http://127.0.0.1:5002}")
    private String activeServiceUrl;

    @Value("${liveness.service.timeout-ms:20000}")
    private int timeoutMs;

    @Value("${liveness.service.fail-open:true}")
    private boolean failOpen;

    @Value("${liveness.service.secret:}")
    private String livenessSecret;

    /** NEW: Liveness mode — "PASSIVE" (default) or "ACTIVE". Runtime-mutable. */
    private volatile String livenessMode = "PASSIVE";

    // ── Dependencies ───────────────────────────────────────────────────────
    @Autowired private LivenessResultRepository livenessRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private ObjectMapper             objectMapper;

    // ── Circuit breaker ────────────────────────────────────────────────────
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long       circuitOpenedAt     = 0L;
    private volatile boolean    circuitOpen         = false;

    // ── Public getters/setters ─────────────────────────────────────────────
    public void    setFailOpen(boolean v)      { this.failOpen    = v; }
    public boolean isFailOpen()                { return failOpen; }
    public boolean isCircuitOpen()             { return circuitOpen; }
    public String  getLivenessMode()           { return livenessMode; }
    public void    setLivenessMode(String m)   {
        if ("PASSIVE".equalsIgnoreCase(m) || "ACTIVE".equalsIgnoreCase(m)) {
            this.livenessMode = m.toUpperCase();
            log.info("[Liveness] Mode set to {}", this.livenessMode);
        } else {
            throw new IllegalArgumentException("livenessMode must be PASSIVE or ACTIVE, got: " + m);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * V2 single-frame endpoint (backward compatibility).
     * Called by ESP32-CAM STREAM:<sessionId> UART command.
     */
    @Transactional
    public boolean processLivenessFrame(String sessionId, String terminalId, byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < MIN_FRAME_BYTES) {
            log.warn("Liveness frame too small ({} bytes) session={}", jpegBytes == null ? 0 : jpegBytes.length, sessionId);
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }
        boolean passed = evaluateWithCircuitBreaker(sessionId, terminalId, jpegBytes,
                () -> callSingleFrameService(sessionId, terminalId, jpegBytes));
        storeResult(sessionId, terminalId, passed, jpegBytes.length);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " mode=SINGLE" + (circuitOpen ? " [CIRCUIT_OPEN]" : ""));
        return passed;
    }

    /**
     * V3 burst endpoint (preferred for PASSIVE mode).
     * Called by ESP32-CAM BURST:<sessionId> UART command.
     */
    @Transactional
    public boolean processBurstLivenessFrames(String sessionId, String terminalId, List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }
        int totalBytes = 0;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (f == null || f.length < MIN_FRAME_BYTES) {
                log.warn("Burst frame {} too small ({} bytes) session={}", i, f == null ? 0 : f.length, sessionId);
                storeResult(sessionId, terminalId, false, 0);
                return false;
            }
            totalBytes += f.length;
        }
        final int finalTotalBytes = totalBytes;
        boolean passed = evaluateWithCircuitBreaker(sessionId, terminalId, frames.get(frames.size() / 2),
                () -> callBurstService(sessionId, terminalId, frames));
        storeResult(sessionId, terminalId, passed, finalTotalBytes);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " mode=BURST frames=" + frames.size() +
                " totalBytes=" + finalTotalBytes + (circuitOpen ? " [CIRCUIT_OPEN]" : ""));
        return passed;
    }

    /**
     * NEW — Active liveness result storage.
     * Called by LivenessStreamHandler when the WebSocket challenge completes
     * (pass or fail / timeout). Stores the result so getLivenessResult() works
     * identically for both PASSIVE and ACTIVE flows.
     *
     * @param sessionId  The S3-generated sessionId (not Spring's WS session ID).
     * @param terminalId Terminal that hosted the session.
     * @param passed     True if the MediaPipe challenge was satisfied.
     */
    @Transactional
    public void storeActiveLivenessResult(String sessionId, String terminalId, boolean passed) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[Liveness] storeActiveLivenessResult called with blank sessionId");
            return;
        }
        storeResult(sessionId, terminalId, passed, 0);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"),
                terminalId != null ? terminalId : "UNKNOWN",
                "SessionID=" + sessionId + " mode=ACTIVE_CHALLENGE");
        log.info("[Liveness] Active result stored: sessionId={} passed={}", sessionId, passed);
    }

    /** Consume and return the liveness result for a session (shared by both modes). */
    @Transactional
    public boolean getLivenessResult(String sessionId) {
        LivenessResult result = livenessRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new EvotingAuthException(
                        "Liveness result not found. Ensure the camera module completed its stream before authentication."));
        if (result.getEvaluatedAt().isBefore(OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES))) {
            throw new EvotingAuthException("Liveness result has expired. Please retry.");
        }
        if (result.isConsumed()) {
            throw new EvotingAuthException("Liveness session already used.");
        }
        result.setConsumed(true);
        livenessRepo.save(result);
        return result.isLivenessPassed();
    }

    // ── Circuit breaker ────────────────────────────────────────────────────

    @FunctionalInterface
    private interface LivenessCall { boolean call() throws Exception; }

    private boolean evaluateWithCircuitBreaker(String sessionId, String terminalId,
                                               byte[] fallbackFrame, LivenessCall call) {
        if (circuitOpen) {
            long now = System.currentTimeMillis();
            if (now - circuitOpenedAt >= CIRCUIT_RECOVERY_INTERVAL_MS) {
                log.info("[Liveness CB] HALF-OPEN — probing {}", livenessServiceUrl);
                try {
                    boolean passed = call.call();
                    circuitOpen = false;
                    consecutiveFailures.set(0);
                    log.info("[Liveness CB] CLOSED — service recovered.");
                    auditLog.log("LIVENESS_CIRCUIT_CLOSED", terminalId, "SessionID=" + sessionId);
                    return passed;
                } catch (Exception e) {
                    circuitOpenedAt = now;
                    log.warn("[Liveness CB] Probe failed — remains OPEN.");
                    return degradedFallback(sessionId, terminalId, fallbackFrame);
                }
            }
            return degradedFallback(sessionId, terminalId, fallbackFrame);
        }
        try {
            boolean passed = call.call();
            consecutiveFailures.set(0);
            return passed;
        } catch (ResourceAccessException e) {
            return handleServiceFailure(sessionId, terminalId, fallbackFrame, e.getMessage());
        } catch (Exception e) {
            log.error("Liveness evaluation error session={}: {}", sessionId, e.getMessage());
            return handleServiceFailure(sessionId, terminalId, fallbackFrame, e.getMessage());
        }
    }

    private boolean handleServiceFailure(String sessionId, String terminalId,
                                         byte[] fallbackFrame, String msg) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("[Liveness CB] Failure #{}: {}", failures, msg);
        if (failures >= CIRCUIT_TRIP_THRESHOLD && !circuitOpen) {
            circuitOpen = true;
            circuitOpenedAt = System.currentTimeMillis();
            log.error("[Liveness CB] OPEN after {} failures.", failures);
            auditLog.log("LIVENESS_CIRCUIT_OPEN", "SYSTEM",
                    "ConsecutiveFailures=" + failures + " Service=" + livenessServiceUrl);
        }
        return degradedFallback(sessionId, terminalId, fallbackFrame);
    }

    private boolean degradedFallback(String sessionId, String terminalId, byte[] jpegBytes) {
        boolean passed = basicJpegValidation(jpegBytes);
        auditLog.log("LIVENESS_CIRCUIT_OPEN", terminalId,
                "SessionID=" + sessionId + " FallbackResult=" + passed + " RequiresManualReview=true");
        log.warn("[Liveness CB] Session {} evaluated in circuit-open state (JPEG-only).", sessionId);
        return passed;
    }

    // ── HTTP calls to Python services ──────────────────────────────────────

    private boolean callSingleFrameService(String sessionId, String terminalId, byte[] jpegBytes) {
        String url = livenessServiceUrl + "/evaluate";
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("frame", namedResource(jpegBytes, "frame.jpg"));
        HttpHeaders headers = buildHeaders(sessionId, terminalId);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> resp = restTemplate()
                .exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return parseLivenessPassed(resp, sessionId);
    }

    private boolean callBurstService(String sessionId, String terminalId, List<byte[]> frames) {
        String url = livenessServiceUrl + "/evaluate-burst";
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (int i = 0; i < frames.size(); i++) {
            final int idx = i;
            body.add("frame" + i, namedResource(frames.get(i), "frame" + idx + ".jpg"));
        }
        HttpHeaders headers = buildHeaders(sessionId, terminalId);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> resp = restTemplate()
                .exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            try {
                JsonNode json      = objectMapper.readTree(resp.getBody());
                boolean  passed    = json.path("livenessPassed").asBoolean(false);
                double   fused     = json.path("confidenceScore").asDouble(0);
                double   flowScore = json.path("flowScore").asDouble(0);
                String   detail    = json.path("detail").asText("unknown");
                log.info("Liveness burst session={} passed={} fused={} flow={} detail={}",
                        sessionId, passed, String.format("%.3f", fused), String.format("%.3f", flowScore), detail);
                return passed;
            } catch (Exception e) {
                log.error("Failed to parse burst liveness response: {}", resp.getBody());
                return false;
            }
        }
        log.warn("Burst liveness service returned {} for session={}", resp.getStatusCode(), sessionId);
        return false;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(String sessionId, String terminalId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Session-Id",  sessionId);
        h.set("X-Terminal-Id", terminalId);
        if (livenessSecret != null && !livenessSecret.isBlank()) {
            h.set("X-Liveness-Secret", livenessSecret);
        } else {
            log.warn("[Liveness] LIVENESS_SECRET not configured — request will be rejected (403).");
        }
        return h;
    }

    private boolean parseLivenessPassed(ResponseEntity<String> resp, String sessionId) {
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            log.warn("Liveness service returned {} for session={}", resp.getStatusCode(), sessionId);
            return false;
        }
        try {
            JsonNode json       = objectMapper.readTree(resp.getBody());
            boolean  passed     = json.path("livenessPassed").asBoolean(false);
            double   confidence = json.path("confidenceScore").asDouble(0.0);
            log.info("Liveness session={} passed={} confidence={}", sessionId, passed, String.format("%.3f", confidence));
            return passed;
        } catch (Exception e) {
            log.error("Failed to parse liveness response: {}", resp.getBody());
            return false;
        }
    }

    private ByteArrayResource namedResource(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override public String getFilename() { return filename; }
        };
    }

    private boolean basicJpegValidation(byte[] b) {
        if (b == null || b.length < 2) return false;
        if (b[0] != (byte)0xFF || b[1] != (byte)0xD8) return false;
        return b.length >= MIN_REAL_FRAME;
    }

    private RestTemplate restTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private void storeResult(String sessionId, String terminalId, boolean passed, int frameBytes) {
        livenessRepo.findBySessionId(sessionId).ifPresent(livenessRepo::delete);
        livenessRepo.save(LivenessResult.builder()
                .sessionId(sessionId).terminalId(terminalId)
                .livenessPassed(passed).frameBytes(frameBytes)
                .evaluatedAt(OffsetDateTime.now()).consumed(false)
                .build());
    }

    /**
     * analyzeFrameForAdmin — proxy a single JPEG frame to the MediaPipe active
     * liveness service and return the raw { passed, challenge } result.
     *
     * Called by BiometricController.analyzeFrame() for the browser-based
     * admin demo in ActiveLivenessView.jsx.
     *
     * This is a lightweight proxy call — it does NOT store a liveness result
     * in the database.  The frontend calls it in a 10 fps loop and declares
     * success on the first { passed: true } response.  Only once the
     * challenge is complete and the frontend stores the session via
     * storeActiveLivenessResult() is a record written.
     *
     * @param sessionId  Browser-generated session UUID (for logging).
     * @param terminalId "BROWSER-ADMIN" or similar label.
     * @param challenge  One of TURN_HEAD_LEFT | TURN_HEAD_RIGHT | SMILE | BLINK.
     * @param jpegBytes  Raw JPEG frame captured from the browser webcam.
     * @return Map with "passed" (boolean) and "challenge" (string).
     */
    public Map<String, Object> analyzeFrameForAdmin(String sessionId,
                                                      String terminalId,
                                                      String challenge,
                                                      byte[] jpegBytes) {
        // Validate challenge name to prevent arbitrary header injection
        java.util.Set<String> valid = java.util.Set.of(
                "TURN_HEAD_LEFT", "TURN_HEAD_RIGHT", "SMILE", "BLINK");
        if (!valid.contains(challenge)) {
            log.warn("[ActiveLiveness] Unknown challenge '{}' from session={}", challenge, sessionId);
            return Map.of("passed", false, "error", "Unknown challenge: " + challenge);
        }

        try {
            String url = activeServiceUrl + "/analyze-frame";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);
            headers.set("X-Session-Id",  sessionId);
            headers.set("X-Terminal-Id", terminalId);
            headers.set("X-Challenge",   challenge);
            if (livenessSecret != null && !livenessSecret.isBlank()) {
                headers.set("X-Liveness-Secret", livenessSecret);
            }

            HttpEntity<byte[]> req = new HttpEntity<>(jpegBytes, headers);
            ResponseEntity<Map> resp = buildRestTemplate()
                    .postForEntity(url, req, Map.class);

            if (resp.getBody() != null) {
                boolean passed = Boolean.TRUE.equals(resp.getBody().get("passed"));
                if (passed) {
                    log.info("[ActiveLiveness] Browser session={} PASSED challenge={}",
                             sessionId, challenge);
                }
                return Map.of("passed",    passed,
                              "challenge", challenge);
            }
            return Map.of("passed", false, "challenge", challenge);

        } catch (Exception e) {
            log.warn("[ActiveLiveness] analyzeFrameForAdmin error: {}", e.getMessage());
            return Map.of("passed", false, "error", e.getMessage());
        }
    }

    // Build a RestTemplate with short timeouts (per-frame calls must be fast)
    private org.springframework.web.client.RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(4_000);
        return new org.springframework.web.client.RestTemplate(factory);
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void cleanupExpiredResults() {
        int deleted = livenessRepo.deleteByEvaluatedAtBefore(
                OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES));
        if (deleted > 0) log.debug("Cleaned up {} expired liveness results", deleted);
    }
}
