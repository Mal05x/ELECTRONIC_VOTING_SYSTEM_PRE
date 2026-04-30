package com.evoting.controller;

import com.evoting.service.BiometricService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Biometric & Liveness API — V3
 *
 * Endpoints:
 *   POST /api/camera/liveness         — V2 single-frame (backward compat)
 *   POST /api/camera/liveness-burst   — V3 5-frame burst (preferred)
 *   GET  /api/camera/ping             — connectivity check
 *   PUT  /api/camera/liveness-config  — runtime fail-open toggle (SUPER_ADMIN)
 *
 * Authentication: No JWT — ESP32-CAM uses mTLS (same client cert as ESP32-S3).
 * The sessionId links the CAM's liveness frame to the S3's auth packet.
 */
@RestController
@RequestMapping("/api/camera")
@Slf4j
public class BiometricController {

    @Autowired private BiometricService biometricService;
    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @Value("${liveness.service.secret:}")
    private String livenessSecret;

    // ─────────────────────────────────────────────────────────────────────────
    //  V2 — Single frame (kept for backward compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/liveness", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submitLivenessFrame(
            @RequestHeader("X-Session-Id")  String sessionId,
            @RequestHeader("X-Terminal-Id") String terminalId,
            @RequestParam("frame")          MultipartFile frame) {

        if (sessionId == null || sessionId.isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "X-Session-Id header is required"));
        if (frame == null || frame.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "frame field is required and must not be empty"));

        try {
            byte[]  jpegBytes = frame.getBytes();
            boolean passed    = biometricService.processLivenessFrame(
                    sessionId, terminalId, jpegBytes);

            return ResponseEntity.ok(Map.of(
                    "sessionId",      sessionId,
                    "livenessPassed", passed,
                    "frameBytes",     jpegBytes.length,
                    "mode",           "SINGLE",
                    "message",        passed ? "Liveness confirmed" : "Liveness check failed"
            ));
        } catch (Exception e) {
            log.error("Liveness frame error session={}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Frame processing failed"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  V3 — 5-frame burst (preferred)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives 5 JPEG frames (frame0..frame4) captured by the ESP32-CAM at
     * 200 ms intervals in response to the BURST:<sessionId> UART command.
     *
     * The Python service fuses:
     *   - Multi-scale MiniFASNet or CDCN++ score on the middle frame
     *   - Optical flow inter-frame motion score across all 5 frames
     *
     * A printed photo produces near-zero inter-frame motion and is rejected
     * even if the single-frame model score is borderline.
     *
     * All 5 parts must be present; any missing frame returns 400.
     */
    @PostMapping(value = "/liveness-burst", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submitBurstFrames(
            @RequestHeader("X-Session-Id")  String sessionId,
            @RequestHeader("X-Terminal-Id") String terminalId,
            @RequestParam("frame0")         MultipartFile frame0,
            @RequestParam("frame1")         MultipartFile frame1,
            @RequestParam("frame2")         MultipartFile frame2,
            @RequestParam("frame3")         MultipartFile frame3,
            @RequestParam("frame4")         MultipartFile frame4) {

        if (sessionId == null || sessionId.isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "X-Session-Id header is required"));

        MultipartFile[] uploads = {frame0, frame1, frame2, frame3, frame4};
        List<byte[]>    frames  = new ArrayList<>(5);

        for (int i = 0; i < uploads.length; i++) {
            if (uploads[i] == null || uploads[i].isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "frame" + i + " is required and must not be empty"));
            }
            try {
                frames.add(uploads[i].getBytes());
            } catch (Exception e) {
                log.error("Failed to read burst frame{} session={}", i, sessionId, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to read frame" + i));
            }
        }

        try {
            boolean passed = biometricService.processBurstLivenessFrames(
                    sessionId, terminalId, frames);

            int totalBytes = frames.stream().mapToInt(b -> b.length).sum();

            return ResponseEntity.ok(Map.of(
                    "sessionId",      sessionId,
                    "livenessPassed", passed,
                    "frameCount",     frames.size(),
                    "totalBytes",     totalBytes,
                    "mode",           "BURST",
                    "message",        passed ? "Liveness confirmed" : "Liveness check failed"
            ));
        } catch (Exception e) {
            log.error("Burst liveness error session={}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Burst frame processing failed"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Diagnostics & Config
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of(
                "status",       "OK",
                "service",      "BiometricService",
                "burstSupport", "true"
        ));
    }

    /**
     * PUT /api/camera/liveness-config
     * Body: { "failOpen": true | false }
     * Requires SUPER_ADMIN. Change is logged in the audit trail.
     */
    @PutMapping(value = "/liveness-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateLivenessConfig(
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        Object raw = body.get("failOpen");
        if (raw == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required field: failOpen (boolean)"));

        boolean failOpen = Boolean.TRUE.equals(raw) ||
                (raw instanceof String s && "true".equalsIgnoreCase(s));

        biometricService.setFailOpen(failOpen);
        log.info("Admin {} set liveness fail-open={}", auth.getName(), failOpen);

        return ResponseEntity.ok(Map.of(
                "failOpen", failOpen,
                "mode",     failOpen ? "WEAK — JPEG fallback enabled"
                        : "STRICT — AI evaluation required",
                "message",  "Liveness configuration updated at runtime."
        ));
    }

    @GetMapping("/liveness-health")
    public ResponseEntity<?> livenessHealth() {
        try {
            HttpHeaders h = new HttpHeaders();
            if (livenessSecret != null && !livenessSecret.isBlank()) {
                h.set("X-Liveness-Secret", livenessSecret);
            }
            RestTemplate rt = new RestTemplate();
            ResponseEntity<String> resp = rt.exchange(
                    livenessServiceUrl + "/health/detail",
                    HttpMethod.GET,
                    new HttpEntity<>(h),
                    String.class
            );
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Liveness service unreachable: " + e.getMessage()));
        }
    }
    
    @PostMapping(value = "/debug-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
     public ResponseEntity<?> debugPreview(
        @RequestParam("frame") MultipartFile frame) {
        try {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("frame", new ByteArrayResource(frame.getBytes()) {
            @Override public String getFilename() { return "frame.jpg"; }
        });

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (livenessSecret != null && !livenessSecret.isBlank()) {
            h.set("X-Liveness-Secret", livenessSecret);
        }

        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> resp = rt.exchange(
            livenessServiceUrl + "/debug/preview",
            HttpMethod.POST,
            new HttpEntity<>(body, h),
            String.class
        );
        return ResponseEntity.status(resp.getStatusCode())
                             .contentType(MediaType.APPLICATION_JSON)
                             .body(resp.getBody());

    } catch (Exception e) {
        return ResponseEntity.status(503)
                .body(Map.of("error", "Debug preview failed: " + e.getMessage()));
    }
  }
}
