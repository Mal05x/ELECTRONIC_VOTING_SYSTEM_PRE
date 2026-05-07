package com.evoting.websocket;

import com.evoting.service.BiometricService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LivenessStreamHandler — Active Liveness WebSocket Handler (v1.2)
 * ================================================================
 * Receives raw JPEG frames from the ESP32-CAM over a persistent WebSocket
 * connection and forwards each frame to the MediaPipe active liveness service
 * (active/app.py on port 5002) for challenge verification.
 *
 * Protocol:
 *   1. ESP32-CAM connects:  wss://<host>/api/camera/stream?sessionId=<id>&terminalId=<id>
 *   2. Server sends JSON:   {"type":"CHALLENGE","action":"TURN_HEAD_LEFT","timeout_seconds":15}
 *   3. CAM streams JPEG frames (binary WebSocket frames) at ~10 fps.
 *   4. Server forwards each frame to Python /analyze-frame (rate-limited to 1 in-flight).
 *   5. On pass, server sends: {"type":"RESULT","status":"SUCCESS"}  and closes socket.
 *   6. Result is stored in liveness_results table via BiometricService.
 *
 * Fixes over v1.0:
 *   BUG-1  SessionId came from Spring's internal WS ID — never matched the S3's sessionId.
 *          Now read from query param: ?sessionId=<S3-generated-UUID>
 *   BUG-2  Result was never persisted to liveness_results table.
 *          Now calls BiometricService.storeActiveLivenessResult() on pass.
 *   BUG-3  X-Liveness-Secret not sent to Python active service — would 403 if auth enabled.
 *          Now reads from ${liveness.service.secret} and adds header.
 *   BUG-4  Python URL was hardcoded to localhost:5001 (conflicts with passive service).
 *          Now reads from ${liveness.active-service.url} (default: http://127.0.0.1:5002).
 *   BUG-5  No session timeout — ESP32 could hold socket open forever.
 *          Sessions older than CHALLENGE_TIMEOUT_SEC are closed by @Scheduled cleaner.
 *   BUG-6  WebSocket CORS set to "*" — narrowed to the same allowed-origin list.
 *   BUG-7  No null-check on Python response body before reading "passed" field.
 */
@Component
@Slf4j
public class LivenessStreamHandler extends AbstractWebSocketHandler {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final int     CHALLENGE_TIMEOUT_SEC = 20;
    private static final String[] CHALLENGES = {
            "TURN_HEAD_LEFT", "TURN_HEAD_RIGHT", "SMILE", "BLINK"
    };

    // ── Config ─────────────────────────────────────────────────────────────
    /** FIX BUG-4: configurable URL, default points to port 5002 (not 5001). */
    @Value("${liveness.active-service.url:http://127.0.0.1:5002}")
    private String activeServiceUrl;

    /** FIX BUG-3: inject same secret used by passive service. */
    @Value("${liveness.service.secret:}")
    private String livenessSecret;

    // ── Dependencies ───────────────────────────────────────────────────────
    @Autowired
    private BiometricService biometricService;

    // ── Internal state ─────────────────────────────────────────────────────
    private final ObjectMapper       mapper        = new ObjectMapper();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Random             random        = new Random();
    private final ExecutorService    aiExecutor    = Executors.newFixedThreadPool(4);

    // ── Session state ──────────────────────────────────────────────────────
    private static class SessionState {
        final String  voterSessionId;   // FIX BUG-1: S3-generated sessionId
        final String  terminalId;
        final String  challenge;
        final Instant createdAt;
        volatile boolean processing = false;

        SessionState(String voterSessionId, String terminalId, String challenge) {
            this.voterSessionId = voterSessionId;
            this.terminalId     = terminalId;
            this.challenge      = challenge;
            this.createdAt      = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().getEpochSecond() - createdAt.getEpochSecond() > CHALLENGE_TIMEOUT_SEC;
        }
    }

    // ── WebSocket lifecycle ────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // FIX BUG-1: Read voter sessionId (and terminalId) from query string.
        // ESP32 connects as: wss://<host>/api/camera/stream?sessionId=<uuid>&terminalId=TERM-KD-001
        String queryString  = session.getUri() != null ? session.getUri().getQuery() : "";
        String voterSession = extractParam(queryString, "sessionId");
        String terminalId   = extractParam(queryString, "terminalId");

        if (voterSession == null || voterSession.isBlank()) {
            log.warn("[ActiveLiveness] Connection rejected — missing sessionId query param");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Pick a random challenge
        String challenge = CHALLENGES[random.nextInt(CHALLENGES.length)];
        sessions.put(session.getId(), new SessionState(voterSession, terminalId, challenge));

        log.info("[ActiveLiveness] ESP32 connected. wsId={} sessionId={} terminal={} challenge={}",
                session.getId(), voterSession, terminalId, challenge);

        // Send challenge to ESP32
        Map<String, Object> msg = Map.of(
                "type",            "CHALLENGE",
                "sessionId",       voterSession,
                "action",          challenge,
                "timeout_seconds", CHALLENGE_TIMEOUT_SEC
        );
        session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null || state.processing || !session.isOpen()) return;

        // FIX BUG-5: reject frames from expired sessions
        if (state.isExpired()) {
            log.warn("[ActiveLiveness] Session {} expired — closing", session.getId());
            closeWithFail(session, state);
            return;
        }

        byte[] jpegBytes  = message.getPayload().array();
        state.processing  = true;  // rate-limit: 1 frame in-flight per session

        aiExecutor.submit(() -> {
            try {
                // FIX BUG-3: add X-Liveness-Secret header
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.IMAGE_JPEG);
                headers.set("X-Session-Id",       state.voterSessionId);
                headers.set("X-Terminal-Id",      state.terminalId != null ? state.terminalId : "unknown");
                headers.set("X-Challenge",         state.challenge);
                if (livenessSecret != null && !livenessSecret.isBlank()) {
                    headers.set("X-Liveness-Secret", livenessSecret);
                }

                // FIX BUG-4: configurable URL pointing to port 5002
                String url = activeServiceUrl + "/analyze-frame";
                HttpEntity<byte[]> req = new HttpEntity<>(jpegBytes, headers);
                ResponseEntity<Map> resp = buildRestTemplate().postForEntity(url, req, Map.class);

                // FIX BUG-7: null-safe body check
                if (resp.getBody() != null && Boolean.TRUE.equals(resp.getBody().get("passed"))) {
                    log.info("[ActiveLiveness] Challenge PASSED — sessionId={} terminal={}",
                            state.voterSessionId, state.terminalId);

                    // FIX BUG-2: persist result so S3 can retrieve it via BiometricService
                    biometricService.storeActiveLivenessResult(
                            state.voterSessionId, state.terminalId, true);

                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                                Map.of("type", "RESULT", "status", "SUCCESS"))));
                        session.close(CloseStatus.NORMAL);
                    }
                }
            } catch (Exception e) {
                log.debug("[ActiveLiveness] Frame evaluation error for session {}: {}",
                        session.getId(), e.getMessage());
            } finally {
                state.processing = false;
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = sessions.remove(session.getId());
        if (state != null) {
            log.info("[ActiveLiveness] Disconnected. wsId={} sessionId={} status={}",
                    session.getId(), state.voterSessionId, status.getCode());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("[ActiveLiveness] Transport error on {}: {}", session.getId(), ex.getMessage());
        sessions.remove(session.getId());
    }

    // ── Scheduled cleanup (FIX BUG-5) ────────────────────────────────────

    /** Close any sessions that have exceeded CHALLENGE_TIMEOUT_SEC. */
    @Scheduled(fixedDelay = 5_000)
    public void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> {
            SessionState state = entry.getValue();
            if (state.isExpired()) {
                log.warn("[ActiveLiveness] Evicting expired session voterSessionId={}",
                        state.voterSessionId);
                // Store a FAIL result so the S3 doesn't hang waiting
                biometricService.storeActiveLivenessResult(
                        state.voterSessionId, state.terminalId, false);
                return true;
            }
            return false;
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void closeWithFail(WebSocketSession session, SessionState state) {
        biometricService.storeActiveLivenessResult(
                state.voterSessionId, state.terminalId, false);
        sessions.remove(session.getId());
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(
                        Map.of("type", "RESULT", "status", "TIMEOUT"))));
                session.close(CloseStatus.NORMAL);
            }
        } catch (Exception ignored) {}
    }

    private String extractParam(String query, String name) {
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(8_000);
        return new RestTemplate(factory);
    }
}
