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

/**
 * Biometric & Liveness Service
 *
 * Integrates with the self-hosted Silent-Face-Anti-Spoofing microservice
 * (MiniFASNet V1 + V2 ONNX models, running on localhost:5001).
 *
 * Flow:
 *   1. ESP32-CAM POSTs raw JPEG to POST /api/camera/liveness with X-Session-Id.
 *   2. BiometricController.java calls processLivenessFrame() here.
 *   3. This service forwards the JPEG to the Python liveness microservice
 *      at POST http://127.0.0.1:5001/evaluate.
 *   4. The Python service runs MiniFASNetV1 + V2; both must score ≥ 0.82 to pass.
 *   5. Result is stored in liveness_results table keyed by sessionId.
 *   6. AuthenticationService calls getLivenessResult(sessionId) to retrieve it.
 *
 * Fail-open policy (configurable):
 *   If the Python service is unreachable and liveness.service.fail-open=true,
 *   the frame falls back to basic JPEG validation (SOI + size checks).
 *   Set fail-open=false in production to enforce AI evaluation always.
 */
@Service
@Slf4j
public class BiometricService {

    private static final int    RESULT_TTL_MINUTES = 10;
    private static final int    MIN_FRAME_BYTES    = 1024;
    private static final int    MIN_REAL_FRAME     = 5000;

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @Value("${liveness.service.timeout-ms:12000}")
    private int timeoutMs;

    @Value("${liveness.service.fail-open:true}")
    private boolean failOpen;

    @Autowired private LivenessResultRepository livenessRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private ObjectMapper             objectMapper;

    // Determines if we bypass strict liveness checks when the AI service is down


    /**
     * Updates the fail-open configuration at runtime.
     */
    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    /**
     * Retrieves the current fail-open configuration.
     */
    public boolean isFailOpen() {
        return this.failOpen;
    }

    private RestTemplate restTemplate() {
        // Timeout-aware RestTemplate — each call to the Python service has a deadline
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);   // 3 s to connect
        factory.setReadTimeout(timeoutMs);  // configurable read timeout
        return new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by BiometricController.java when the ESP32-CAM posts its JPEG frame.
     * Evaluates liveness via the Python microservice and stores the result.
     */
    @Transactional
    public boolean processLivenessFrame(String sessionId, String terminalId, byte[] jpegBytes) {

        // Reject obviously bad payloads immediately
        if (jpegBytes == null || jpegBytes.length < MIN_FRAME_BYTES) {
            log.warn("Liveness frame too small ({} bytes) for session {}",
                    jpegBytes == null ? 0 : jpegBytes.length, sessionId);
            storeResult(sessionId, terminalId, false, 0);
            return false;
        }

        boolean passed;
        try {
            passed = callLivenessService(sessionId, terminalId, jpegBytes);
        } catch (ResourceAccessException e) {
            // Python service unreachable
            if (failOpen) {
                log.warn("Liveness service unreachable for session {} — " +
                        "failing open with basic JPEG validation (fail-open=true)", sessionId);
                auditLog.log("LIVENESS_FALLBACK", terminalId,
                        "SessionID=" + sessionId + " reason=service_unreachable");
                passed = basicJpegValidation(jpegBytes);
            } else {
                log.error("Liveness service unreachable for session {} (fail-open=false) — " +
                        "rejecting frame", sessionId);
                auditLog.log("LIVENESS_FAIL_SERVICE_DOWN", terminalId,
                        "SessionID=" + sessionId);
                passed = false;
            }
        } catch (Exception e) {
            log.error("Liveness evaluation error for session {}: {}", sessionId, e.getMessage());
            passed = failOpen && basicJpegValidation(jpegBytes);
        }

        storeResult(sessionId, terminalId, passed, jpegBytes.length);
        auditLog.log("LIVENESS_" + (passed ? "PASS" : "FAIL"), terminalId,
                "SessionID=" + sessionId + " frameBytes=" + jpegBytes.length);
        return passed;
    }

    /**
     * Called by AuthenticationService to retrieve the result for a sessionId.
     * Marks the result consumed to prevent session replay.
     */
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
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calls POST http://127.0.0.1:5001/evaluate with the JPEG as multipart/form-data.
     *
     * The Python FastAPI service:
     *   1. Detects the face using OpenCV Haar cascade
     *   2. Crops + resizes to 80×80
     *   3. Runs MiniFASNetV1 (2.7 MB) — fast pass
     *   4. Runs MiniFASNetV2 (12 MB)  — accuracy check
     *   5. Both models must score ≥ 0.82 for livenessPassed=true
     *
     * Response shape:
     *   { "livenessPassed": bool, "score_v1": float, "score_v2": float,
     *     "detail": str, "elapsedMs": int }
     */
    private boolean callLivenessService(String sessionId, String terminalId,
                                        byte[] jpegBytes) {
        String url = livenessServiceUrl + "/evaluate";

        // Build multipart request
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource frameResource = new ByteArrayResource(jpegBytes) {
            @Override
            public String getFilename() {
                return "frame.jpg";
            }
        };
        body.add("frame", frameResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Session-Id", sessionId);
        headers.set("X-Terminal-Id", terminalId);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate()
                .exchange(url, HttpMethod.POST, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Liveness service returned {} for session {}",
                    response.getStatusCode(), sessionId);
            return false;
        }

        // Parse response
        JsonNode json = null;
        try {
            json = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("Failed to parse liveness service response: {}", response.getBody());
            return false;
        }

        boolean passed = json.path("livenessPassed").asBoolean(false);
        double confidence = json.path("confidenceScore").asDouble(0.0);

        log.info("Liveness AI session={} passed={} confidence={:.3f}",
                sessionId, passed, confidence);

        return passed;
    }

    /**
     * Basic JPEG validation used as fallback when the Python service is unreachable.
     * Only checks that the frame is a valid JPEG with plausible face content size.
     * This is intentionally weaker than the AI model — log LIVENESS_FALLBACK
     * events should be monitored and the Python service should be restored ASAP.
     */
    private boolean basicJpegValidation(byte[] jpegBytes) {
        if (jpegBytes.length < 2) return false;
        if (jpegBytes[0] != (byte)0xFF || jpegBytes[1] != (byte)0xD8) {
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

    /** Scheduled cleanup: delete liveness results older than TTL. */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void cleanupExpiredResults() {
        int deleted = livenessRepo.deleteByEvaluatedAtBefore(
                OffsetDateTime.now().minusMinutes(RESULT_TTL_MINUTES));
        if (deleted > 0) log.debug("Cleaned up {} expired liveness results", deleted);
    }
}
