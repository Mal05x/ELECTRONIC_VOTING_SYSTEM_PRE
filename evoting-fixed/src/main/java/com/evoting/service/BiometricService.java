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
 * Biometric & Liveness Service (V3)
 *
 * V3 additions over V2:
 *   ✓ processBurstLivenessFrames() — sends 5 JPEG frames to /evaluate-burst.
 *     The Python service fuses the multi-scale MiniFASNet (or CDCN++) score with
 *     an optical flow motion score. A static printed photo produces near-zero
 *     inter-frame flow and fails regardless of model confidence.
 *   ✓ /evaluate (single frame) kept for backward-compat via processLivenessFrame().
 *   ✓ Circuit breaker now covers both endpoints identically.
 *
 * Circuit breaker states (unchanged from V2):
 *   CLOSED   → normal; AI evaluation active.
 *   OPEN     → tripped after CIRCUIT_TRIP_THRESHOLD failures; falls back to
 *              basicJpegValidation(). Every fallback decision audit-logged as
 *              LIVENESS_CIRCUIT_OPEN.
 *   HALF-OPEN → one probe after CIRCUIT_RECOVERY_INTERVAL_MS; closes on success.
 */
@Service
@Slf4j
public class BiometricService {

    private static final int  RESULT_TTL_MINUTES           = 10;
    private static final int  MIN_FRAME_BYTES              = 1024;
    private static final int  MIN_REAL_FRAME               = 5000;
    private static final int  CIRCUIT_TRIP_THRESHOLD       = 3;
    private static final long CIRCUIT_RECOVERY_INTERVAL_MS = 60_000L;

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @Value("${liveness.service.timeout-ms:20000}")   // V3: higher — burst carries 5 frames
    private int timeoutMs;

    @Value("${liveness.service.fail-open:true}")
    private boolean failOpen;

    @Value("${liveness.service.secret:}")
    private String livenessSecret;

    @Autowired private LivenessResultRepository livenessRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private ObjectMapper             objectMapper;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long       circuitOpenedAt     = 0L;
    private volatile boolean    circuitOpen         = false;

    public void    setFailOpen(boolean v) { this.failOpen = v; }
    public boolean isFailOpen()           { return this.failOpen; }
    public boolean isCircuitOpen()        { return circuitOpen; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * V2 single-frame endpoint — kept for backward compatibility.
     * Called by ESP32-CAM STREAM:<sessionId> command.
     */
    @Transactional
    public boolean processLivenessFrame(String sessionId, String terminalId,
                                        byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < MIN_FRAME_BYTES) {
            log.warn("Liveness frame too small ({} bytes) session={}",
                    jpegBytes == null ? 0 : jpegBytes.length, sessionId);
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }

        boolean passed = evaluateWithCircuitBreaker(
                sessionId, terminalId, jpegBytes,
                () -> callSingleFrameService(sessionId, terminalId, jpegBytes)
        );

        storeResult(sessionId, terminalId, passed, jpegBytes.length);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " mode=SINGLE" +
                        " frameBytes=" + jpegBytes.length +
                        (circuitOpen ? " [CIRCUIT_OPEN]" : ""));
        return passed;
    }

    /**
     * V3 burst endpoint — preferred path.
     * Called by ESP32-CAM BURST:<sessionId> command.
     * Accepts 5 JPEG frames captured at 200 ms intervals.
     * Python service adds optical flow motion score to the liveness decision.
     *
     * @param frames  Ordered list of 5 JPEG byte arrays (frame0..frame4).
     */
    @Transactional
    public boolean processBurstLivenessFrames(String sessionId, String terminalId,
                                              List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            log.warn("Burst liveness called with no frames for session={}", sessionId);
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }

        // Reject any frame that is suspiciously small
        int totalBytes = 0;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (f == null || f.length < MIN_FRAME_BYTES) {
                log.warn("Burst frame {} too small ({} bytes) session={}",
                        i, f == null ? 0 : f.length, sessionId);
                storeResult(sessionId, terminalId, false, 0);
                return false;
            }
            totalBytes += f.length;
        }

        final int finalTotalBytes = totalBytes;
        boolean passed = evaluateWithCircuitBreaker(
                sessionId, terminalId, frames.get(frames.size() / 2),  // middle for fallback
                () -> callBurstService(sessionId, terminalId, frames)
        );

        storeResult(sessionId, terminalId, passed, finalTotalBytes);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " mode=BURST" +
                        " frameCount=" + frames.size() +
                        " totalBytes=" + finalTotalBytes +
                        (circuitOpen ? " [CIRCUIT_OPEN]" : ""));
        return passed;
    }

    @Transactional
    public boolean getLivenessResult(String sessionId) {
        LivenessResult result = livenessRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new EvotingAuthException(
                        "Liveness result not found. " +
                                "Ensure the camera module completed its stream before authentication."));

        if (result.getEvaluatedAt()
                .isBefore(OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES))) {
            throw new EvotingAuthException("Liveness result has expired. Please retry.");
        }
        if (result.isConsumed()) {
            throw new EvotingAuthException("Liveness session already used.");
        }
        result.setConsumed(true);
        livenessRepo.save(result);
        return result.isLivenessPassed();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Circuit breaker
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface LivenessCall { boolean call() throws Exception; }

    private boolean evaluateWithCircuitBreaker(String sessionId, String terminalId,
                                               byte[] fallbackFrame,
                                               LivenessCall call) {
        if (circuitOpen) {
            long now = System.currentTimeMillis();
            if (now - circuitOpenedAt >= CIRCUIT_RECOVERY_INTERVAL_MS) {
                log.info("[Liveness CB] HALF-OPEN — probing {}", livenessServiceUrl);
                try {
                    boolean passed = call.call();
                    circuitOpen = false;
                    consecutiveFailures.set(0);
                    log.info("[Liveness CB] CLOSED — service recovered.");
                    auditLog.log("LIVENESS_CIRCUIT_CLOSED", terminalId,
                            "SessionID=" + sessionId);
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
            circuitOpen     = true;
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
                "SessionID=" + sessionId +
                        " FallbackResult=" + passed + " RequiresManualReview=true");
        log.warn("[Liveness CB] Session {} evaluated in circuit-open state (JPEG-only).", sessionId);
        return passed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP calls to Python service
    // ─────────────────────────────────────────────────────────────────────────

    /** Single-frame call → POST /evaluate */
    private boolean callSingleFrameService(String sessionId, String terminalId,
                                           byte[] jpegBytes) {
        String url = livenessServiceUrl + "/evaluate";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("frame", namedResource(jpegBytes, "frame.jpg"));

        HttpHeaders headers = buildHeaders(sessionId, terminalId);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = restTemplate()
                .exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        return parseLivenessPassed(resp, sessionId);
    }

    /** Burst call → POST /evaluate-burst (5 frames as separate multipart fields) */
    private boolean callBurstService(String sessionId, String terminalId,
                                     List<byte[]> frames) {
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
                JsonNode json = objectMapper.readTree(resp.getBody());
                boolean passed     = json.path("livenessPassed").asBoolean(false);
                double  fused      = json.path("confidenceScore").asDouble(0);
                double  flowScore  = json.path("flowScore").asDouble(0);
                String  detail     = json.path("detail").asText("unknown");
                log.info("Liveness burst session={} passed={} fused={} flow={} detail={}",
                        sessionId, passed,
                        String.format("%.3f", fused),
                        String.format("%.3f", flowScore),
                        detail);
                return passed;
            } catch (Exception e) {
                log.error("Failed to parse burst liveness response: {}", resp.getBody());
                return false;
            }
        }
        log.warn("Burst liveness service returned {} for session={}",
                resp.getStatusCode(), sessionId);
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

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
            log.info("Liveness session={} passed={} confidence={}", sessionId, passed,
                    String.format("%.3f", confidence));
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
        if (b == null || b.length < 2)    return false;
        if (b[0] != (byte)0xFF || b[1] != (byte)0xD8) return false;
        return b.length >= MIN_REAL_FRAME;
    }

    private RestTemplate restTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private void storeResult(String sessionId, String terminalId,
                             boolean passed, int frameBytes) {
        livenessRepo.findBySessionId(sessionId).ifPresent(livenessRepo::delete);
        livenessRepo.save(LivenessResult.builder()
                .sessionId(sessionId).terminalId(terminalId)
                .livenessPassed(passed).frameBytes(frameBytes)
                .evaluatedAt(OffsetDateTime.now()).consumed(false)
                .build());
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void cleanupExpiredResults() {
        int deleted = livenessRepo.deleteByEvaluatedAtBefore(
                OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES));
        if (deleted > 0) log.debug("Cleaned up {} expired liveness results", deleted);
    }
}
