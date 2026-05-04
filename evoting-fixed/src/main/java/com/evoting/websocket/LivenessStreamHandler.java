package com.evoting.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class LivenessStreamHandler extends AbstractWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ChallengeState> activeSessions = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final RestTemplate restTemplate = new RestTemplate();

    // Thread pool so we don't block the WebSocket receiving frames
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(4);

    // Point this specifically to the new MediaPipe model on Port 5001
    private final String PYTHON_AI_URL = "http://localhost:5001/analyze-frame";

    // The OPay-style randomized challenges
    private final String[] CHALLENGES = {"TURN_HEAD_LEFT", "TURN_HEAD_RIGHT", "SMILE", "BLINK"};

    // Helper class to store session state and prevent flooding Python with frames
    private static class ChallengeState {
        String challenge;
        boolean isProcessing = false;
        public ChallengeState(String challenge) { this.challenge = challenge; }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 1. Pick a random challenge
        String selectedChallenge = CHALLENGES[random.nextInt(CHALLENGES.length)];
        activeSessions.put(session.getId(), new ChallengeState(selectedChallenge));

        System.out.println("[WEBSOCKET] ESP32 Connected! Session: " + session.getId() + " | Challenge: " + selectedChallenge);

        // 2. Build the JSON handshake payload
        Map<String, Object> msg = Map.of(
                "type", "CHALLENGE",
                "sessionId", session.getId(),
                "action", selectedChallenge,
                "timeout_seconds", 10
        );

        // 3. Fire the challenge down the pipe to the ESP32
        session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = session.getId();
        ChallengeState state = activeSessions.get(sessionId);

        // If we are already processing a frame for this user, drop this new one to prevent lag!
        if (state == null || state.isProcessing || !session.isOpen()) return;

        byte[] jpegBytes = message.getPayload().array();
        state.isProcessing = true; // Lock the session

        aiExecutor.submit(() -> {
            try {
                // Forward the raw JPEG and the Challenge to Python
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.IMAGE_JPEG);
                headers.set("X-Session-Id", sessionId);
                headers.set("X-Challenge", state.challenge);

                HttpEntity<byte[]> request = new HttpEntity<>(jpegBytes, headers);
                ResponseEntity<Map> response = restTemplate.postForEntity(PYTHON_AI_URL, request, Map.class);

                // If Python says they passed the challenge!
                if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("passed"))) {
                    System.out.println("[WEBSOCKET] AI passed session " + sessionId + "!");

                    // Tell the ESP32 to shut down the camera (Success!)
                    Map<String, String> successMsg = Map.of("type", "RESULT", "status", "SUCCESS");
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(successMsg)));

                    // Close the socket gracefully
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception e) {
                // Silently ignore connection errors in case Python is still booting
            } finally {
                state.isProcessing = false; // Unlock so we can catch the next frame
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        System.out.println("[WEBSOCKET] ESP32 Disconnected. Session: " + session.getId());
    }
}