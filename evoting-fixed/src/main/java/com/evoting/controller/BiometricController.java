package com.evoting.controller;

import com.evoting.service.BiometricService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

/**
 * Biometric & Liveness API — called directly by the ESP32-CAM over Wi-Fi.
 *
 * No JWT required: the camera module uses mTLS (client certificate) for
 * authentication, same as the ESP32-S3. The sessionId ties the camera's
 * liveness frame to the ESP32-S3's authentication packet.
 */
@RestController
@RequestMapping("/api/camera")
@Slf4j
public class BiometricController {

    @Autowired private BiometricService biometricService;

    /**
     * Receives a raw JPEG frame from the ESP32-CAM.
     * Evaluates liveness server-side and stores the result keyed by sessionId.
     */
    @PostMapping(value = "/liveness", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submitLivenessFrame(
            @RequestHeader("X-Session-Id")  String sessionId,
            @RequestHeader("X-Terminal-Id") String terminalId,
            @RequestParam("frame")          MultipartFile frame) {

        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "X-Session-Id header is required"));
        }
        if (frame == null || frame.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "frame field is required and must not be empty"));
        }

        try {
            byte[] jpegBytes = frame.getBytes();
            boolean passed   = biometricService.processLivenessFrame(
                    sessionId, terminalId, jpegBytes);

            return ResponseEntity.ok(Map.of(
                    "sessionId",      sessionId,
                    "livenessPassed", passed,
                    "frameBytes",     jpegBytes.length,
                    "message",        passed ? "Liveness confirmed" : "Liveness check failed"
            ));
        } catch (Exception e) {
            log.error("Liveness frame processing error for session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Frame processing failed"));
        }
    }

    /**
     * Health check endpoint — ESP32-CAM can ping this on startup
     * to confirm the backend is reachable before attempting liveness streaming.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "OK", "service", "BiometricService"));
    }

    /**
     * PUT /api/camera/liveness-config
     *
     * Updates the liveness fail-open flag at runtime without a server restart.
     * Body: { "failOpen": true | false }
     *
     * failOpen=false (strict): if MiniFASNet service is unreachable, vote is blocked.
     * failOpen=true  (weak):   if MiniFASNet service is unreachable, basic JPEG
     *                          validation is used as fallback. Only for lab testing.
     *
     * Requires SUPER_ADMIN role. Change is logged in the audit trail.
     */
    @PutMapping(value = "/liveness-config",
            consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateLivenessConfig(
            @RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication auth) {

        Object raw = body.get("failOpen");
        if (raw == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required field: failOpen (boolean)"));
        }

        boolean failOpen = Boolean.TRUE.equals(raw) ||
                (raw instanceof String && "true".equalsIgnoreCase((String) raw));

        biometricService.setFailOpen(failOpen);

        log.info("Admin {} updated liveness fail-open to {}", auth.getName(), failOpen);

        return ResponseEntity.ok(Map.of(
                "failOpen", failOpen,
                "mode",     failOpen ? "WEAK — JPEG fallback enabled" : "STRICT — AI evaluation required",
                "message",  "Liveness configuration updated at runtime. No restart required."
        ));
    }
} // <-- This is the closing bracket for the entire class!