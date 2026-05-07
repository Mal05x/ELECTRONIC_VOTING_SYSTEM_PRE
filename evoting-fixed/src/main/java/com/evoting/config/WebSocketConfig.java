package com.evoting.config;

import com.evoting.websocket.LivenessStreamHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
@EnableWebSocket
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    @Value("${websocket.allowed-origin:http://localhost:3000,http://localhost:5173,https://localhost:3000,https://localhost:5173,http://localhost:4173,https://localhost:4173,https://192.168.0.159:3000}")
    private String allowedOrigin;

    private final LivenessStreamHandler livenessStreamHandler;

    @Autowired
    public WebSocketConfig(LivenessStreamHandler livenessStreamHandler) {
        this.livenessStreamHandler = livenessStreamHandler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = java.util.Arrays.stream(allowedOrigin.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOrigins(origins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // FIX BUG-6: Replaced "*" with the same controlled origin list.
        // The ESP32-CAM doesn't send an Origin header so Spring defaults to allowing it.
        // Using "*" in production allows any browser-based WebSocket connection to
        // stream arbitrary JPEG data into the liveness pipeline.
        String[] origins = java.util.Arrays.stream(allowedOrigin.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new);

        registry.addHandler(livenessStreamHandler, "/api/camera/stream")
                .setAllowedOrigins(origins);
    }
}
