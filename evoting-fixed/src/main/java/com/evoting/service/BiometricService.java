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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Biometric & Liveness Service
 *
 * Integrates with the self-hosted Silent-Face-Anti-Spoofing microservice
 * (MiniFASNetV2_SE ONNX model, running via Docker at liveness.service.url).
 *
 * ── FIX DQ2: Shared secret authentication ────────────────────────────────────
 *
 * BEFORE:
 *   The Python /evaluate endpoint accepted any request from the internal network.
 *   Any component (or attacker with network access) could submit arbitrary frames
 *   and receive liveness decisions without authentication.
 *
 * AFTER:
 *   Every call to /evaluate includes header: X-Liveness-Secret: <secret>
 *   The Python service validates this secret and rejects requests with 403.
 *   Secret is configured via: liveness.service.secret (env: LIVENESS_SECRET)
 *   Generate with: openssl rand -hex 32
 *   Set the SAME value in Spring's LIVENESS_SECRET env var and the
 *   liveness-service/.env file (LIVENESS_SECRET=...).
 *
 * ── FIX DQ4: Circuit breaker for liveness single point of failure ─────────────
 *
 * BEFORE:
 *   fail-open=false caused a Python service crash to block ALL voting nationwide.
 *   No automatic recovery or degraded-mode transition existed.
 *
 * AFTER: A simple circuit breaker with three states:
 *
 *   CLOSED  (normal) — service is reachable; AI evaluation active.
 *   OPEN    (tripped) — service has failed CIRCUIT_TRIP_THRESHOLD times
 *                       consecutively; requests skip the AI call and use
 *                       basicJpegValidation() as the fallback, regardless of
 *                       the fail-open configuration. Every liveness decision
 *                       made in OPEN state is audit-logged as LIVENESS_CIRCUIT_OPEN
 *                       so operators can identify and review affected sessions.
 *   HALF-OPEN (probing) — after CIRCUIT_RECOVERY_INTERVAL_MS, one probe request
 *                         is sent. If it succeeds, the circuit closes. If it fails,
 *                         the open timer resets.
 *
 * This prevents a Python service outage from stopping an election, while ensuring
 * every degraded-mode decision is visible in the audit log for post-election review.
 *
 * Tuning:
 *   CIRCUIT_TRIP_THRESHOLD       — failures before tripping (default: 3)
 *   CIRCUIT_RECOVERY_INTERVAL_MS — probe interval after trip (default: 60 seconds)
 */
@Service
@Slf4j
public class BiometricService {

    private static final int  RESULT_TTL_MINUTES            = 10;
    private static final int  MIN_FRAME_BYTES               = 1024;
    private static final int  MIN_REAL_FRAME                = 5000;

    // ── FIX DQ4: Circuit breaker constants ───────────────────────────────────
    private static final int  CIRCUIT_TRIP_THRESHOLD        = 3;
    private static final long CIRCUIT_RECOVERY_INTERVAL_MS  = 60_000L; // 1 minute

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @Value("${liveness.service.timeout-ms:12000}")
    private int timeoutMs;

    @Value("${liveness.service.fail-open:true}")
    private boolean failOpen;

    // ── FIX DQ2: Shared secret ────────────────────────────────────────────────
    @Value("${liveness.service.secret:}")
    private String livenessSecret;

    @Autowired private LivenessResultRepository livenessRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private ObjectMapper             objectMapper;

    // ── FIX DQ4: Circuit breaker state ───────────────────────────────────────
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long       circuitOpenedAt     = 0L;
    private volatile boolean    circuitOpen         = false;

    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public boolean isFailOpen() { return this.failOpen; }

    /** Returns true if the circuit breaker is currently open (service considered down). */
    public boolean isCircuitOpen() { return circuitOpen; }

    private RestTemplate restTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public boolean processLivenessFrame(String sessionId, String terminalId, byte[] jpegBytes) {

        if (jpegBytes == null || jpegBytes.length < MIN_FRAME_BYTES) {
            log.warn("Liveness frame too small ({} bytes) for session {}",
                    jpegBytes == null ? 0 : jpegBytes.length, sessionId);
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }

        boolean passed;

        // ── FIX DQ4: Check circuit breaker state ─────────────────────────────
        if (circuitOpen) {
            long now = System.currentTimeMillis();
            if (now - circuitOpenedAt >= CIRCUIT_RECOVERY_INTERVAL_MS) {
                // Transition to HALF-OPEN: try one probe
                log.info("[Liveness CB] Circuit HALF-OPEN — probing liveness service at {}",
                        livenessServiceUrl);
                try {
                    passed = callLivenessService(sessionId, terminalId, jpegBytes);
                    // Probe succeeded — close circuit
                    circuitOpen = false;
                    consecutiveFailures.set(0);
                    log.info("[Liveness CB] Circuit CLOSED — liveness service recovered.");
                    auditLog.log("LIVENESS_CIRCUIT_CLOSED", terminalId,
                            "SessionID=" + sessionId + " Service=" + livenessServiceUrl);
                } catch (Exception e) {
                    // Probe failed — reset open timer
                    circuitOpenedAt = now;
                    log.warn("[Liveness CB] Probe failed — circuit remains OPEN. Next probe in {}s",
                            CIRCUIT_RECOVERY_INTERVAL_MS / 1000);
                    passed = degradedFallback(sessionId, terminalId, jpegBytes);
                }
            } else {
                // Circuit open, not time to probe yet — skip AI call
                passed = degradedFallback(sessionId, terminalId, jpegBytes);
            }
        } else {
            // Normal path — circuit CLOSED
            try {
                passed = callLivenessService(sessionId, terminalId, jpegBytes);
                // Successful call — reset failure counter
                consecutiveFailures.set(0);
            } catch (ResourceAccessException e) {
                passed = handleServiceFailure(sessionId, terminalId, jpegBytes, e.getMessage());
            } catch (Exception e) {
                log.error("Liveness evaluation error for session {}: {}", sessionId, e.getMessage());
                passed = handleServiceFailure(sessionId, terminalId, jpegBytes, e.getMessage());
            }
        }

        storeResult(sessionId, terminalId, passed, jpegBytes.length);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " frameBytes=" + jpegBytes.length +
                        (circuitOpen ? " [CIRCUIT_OPEN]" : ""));
        return passed;
    }

    /** Called on service failure — updates circuit breaker state. */
    private boolean handleServiceFailure(String sessionId, String terminalId,
                                         byte[] jpegBytes, String errorMsg) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("[Liveness CB] Service failure #{}: {}", failures, errorMsg);

        if (failures >= CIRCUIT_TRIP_THRESHOLD && !circuitOpen) {
            circuitOpen     = true;
            circuitOpenedAt = System.currentTimeMillis();
            log.error("[Liveness CB] Circuit OPEN after {} consecutive failures. " +
                            "Falling back to basicJpegValidation for all sessions until service recovers. " +
                            "All fallback decisions are audit-logged as LIVENESS_CIRCUIT_OPEN.",
                    failures);
            auditLog.log("LIVENESS_CIRCUIT_OPEN", "SYSTEM",
                    "ConsecutiveFailures=" + failures + " Service=" + livenessServiceUrl);
        }

        return degradedFallback(sessionId, terminalId, jpegBytes);
    }

    /**
     * Degraded-mode fallback used when the circuit is open.
     * Uses basicJpegValidation regardless of fail-open config.
     * Every call is audit-logged so operators know which sessions were evaluated
     * without AI and can manually review them post-election if needed.
     */
    private boolean degradedFallback(String sessionId, String terminalId, byte[] jpegBytes) {
        boolean passed = basicJpegValidation(jpegBytes);
        auditLog.log("LIVENESS_CIRCUIT_OPEN", terminalId,
                "SessionID=" + sessionId +
                        " FallbackResult=" + passed +
                        " RequiresManualReview=true");
        log.warn("[Liveness CB] Session {} evaluated in circuit-open state (JPEG-only fallback). " +
                "Manual review recommended.", sessionId);
        return passed;
    }

    @Transactional
    public boolean getLivenessResult(String sessionId) {
        LivenessResult result = livenessRepo.findBySessionId(sessionId)
                .orElseThrow(() -> {
                    log.warn("No liveness result for sessionId={}", sessionId);
                    return new EvotingAuthException(
                            "Liveness result not found. " +
                                    "Ensure the camera module completed its stream before authentication.");
                });

        if (result.getEvaluatedAt()
                .isBefore(OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES))) {
            log.warn("Liveness result expired for sessionId={}", sessionId);
            throw new EvotingAuthException("Liveness result has expired. Please retry.");
        }

        if (result.isConsumed()) {
            log.warn("Liveness result already consumed for sessionId={}", sessionId);
            throw new EvotingAuthException("Liveness session already used.");
        }

        result.setConsumed(true);
        livenessRepo.save(result);
        return result.isLivenessPassed();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean callLivenessService(String sessionId, String terminalId,
                                        byte[] jpegBytes) {
        String url = livenessServiceUrl + "/evaluate";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource frameResource = new ByteArrayResource(jpegBytes) {
            @Override public String getFilename() { return "frame.jpg"; }
        };
        body.add("frame", frameResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Session-Id",  sessionId);
        headers.set("X-Terminal-Id", terminalId);

        // ── FIX DQ2: Attach shared secret ────────────────────────────────────
        if (livenessSecret != null && !livenessSecret.isBlank()) {
            headers.set("X-Liveness-Secret", livenessSecret);
        } else {
            log.warn("[Liveness] LIVENESS_SECRET not configured — " +
                    "request will be rejected by the Python service (403).");
        }

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate()
                .exchange(url, HttpMethod.POST, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Liveness service returned {} for session {}",
                    response.getStatusCode(), sessionId);
            return false;
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("Failed to parse liveness service response: {}", response.getBody());
            return false;
        }

        boolean passed    = json.path("livenessPassed").asBoolean(false);
        double confidence = json.path("confidenceScore").asDouble(0.0);
        log.info("Liveness AI session={} passed={} confidence={}", sessionId, passed,
                String.format("%.3f", confidence));
        return passed;
    }

    private boolean basicJpegValidation(byte[] jpegBytes) {
        if (jpegBytes.length < 2) return false;
        if (jpegBytes[0] != (byte) 0xFF || jpegBytes[1] != (byte) 0xD8) {
            log.warn("Basic JPEG validation: invalid SOI marker");
            return false;
        }
        if (jpegBytes.length < MIN_REAL_FRAME) {
            log.warn("Basic JPEG validation: frame too small ({} bytes)", jpegBytes.length);
            return false;
        }
        return true;
    }

    private void storeResult(String sessionId, String terminalId,
                             boolean passed, int frameBytes) {
        livenessRepo.findBySessionId(sessionId).ifPresent(livenessRepo::delete);
        livenessRepo.save(LivenessResult.builder()
                .sessionId(sessionId)
                .terminalId(terminalId)
                .livenessPassed(passed)
                .frameBytes(frameBytes)
                .evaluatedAt(OffsetDateTime.now())
                .consumed(false)
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
