package com.evoting.controller;

import com.evoting.service.TerminalAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * TerminalPingController
 * ======================
 * Lightweight endpoint used by terminal_ping_test.ino to verify:
 *   1. TLS connection reaches Spring Boot
 *   2. Terminal signature is valid (key is registered)
 *   3. Timestamp is within acceptable skew
 *
 * POST /api/terminal/ping
 *   Headers:
 *     X-Terminal-Id  : TERM-KD-001
 *     X-Timestamp    : Unix seconds
 *     X-Signature    : Base64 P1363 ECDSA-P256 signature
 *   Body:
 *     { "terminalId": "TERM-KD-001", "timestamp": 1700000000 }
 *
 * Responses:
 *   200 { "status": "pong", "terminalId": "...", "serverTime": ... }
 *   401 { "error": "Invalid signature" }
 *   400 { "error": "Timestamp skew too large" }
 *   404 if terminal not registered
 */
@RestController
@RequestMapping("/api/terminal")
@Slf4j
public class TerminalPingController {

    // Maximum allowed clock skew between terminal and server (seconds)
    private static final long MAX_SKEW_SECONDS = 300;   // 5 minutes

    @Autowired
    private TerminalAuthService terminalAuthService;

    @PostMapping("/ping")
    public ResponseEntity<?> ping(
            @RequestHeader("X-Terminal-Id")  String terminalId,
            @RequestHeader("X-Timestamp")    String timestampStr,
            @RequestHeader("X-Signature")    String signature,
            @RequestBody                     Map<String, Object> body) {

        // ── 1. Parse timestamp ────────────────────────────────────────────────
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid X-Timestamp header"));
        }

        // ── 2. Clock skew check ───────────────────────────────────────────────
        long serverTime = Instant.now().getEpochSecond();
        long skew = Math.abs(serverTime - timestamp);
        if (skew > MAX_SKEW_SECONDS) {
            log.warn("[PING] Terminal {} clock skew {}s exceeds {}s limit",
                    terminalId, skew, MAX_SKEW_SECONDS);
            return ResponseEntity.badRequest().body(Map.of(
                    "error",      "Timestamp skew too large",
                    "skewSeconds", skew,
                    "maxAllowed",  MAX_SKEW_SECONDS,
                    "hint",        "Check NTP sync on terminal (Test 5 in ping test)"
            ));
        }

        // ── 3. Signature verification ─────────────────────────────────────────
        // Canonical: terminalId|timestamp|base64Sha256({body})
        // Body for ping is the raw JSON string — compute its SHA-256 hash
        String bodyJson = "{\"terminalId\":\"" + terminalId
                + "\",\"timestamp\":" + timestamp + "}";

        boolean valid = terminalAuthService.verifyPingSignature(
                terminalId, timestamp, signature, bodyJson);

        if (!valid) {
            log.warn("[PING] Signature verification FAILED for terminal {}", terminalId);
            return ResponseEntity.status(401).body(Map.of(
                    "error",  "Invalid signature",
                    "hint",   "Ensure the terminal public key is registered "
                            + "in Admin Dashboard → Terminals"
            ));
        }

        // ── 4. Success ────────────────────────────────────────────────────────
        log.info("[PING] Terminal {} ping verified OK (skew={}s)", terminalId, skew);

        return ResponseEntity.ok(Map.of(
                "status",     "pong",
                "terminalId", terminalId,
                "serverTime", serverTime,
                "skewSeconds", skew,
                "message",    "Terminal signature valid. NVS keys and TLS working correctly."
        ));
    }
}
