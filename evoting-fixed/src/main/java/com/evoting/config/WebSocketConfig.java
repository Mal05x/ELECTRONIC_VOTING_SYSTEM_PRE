package com.evoting.config;

import com.evoting.websocket.LivenessStreamHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker // Keeps your existing STOMP endpoints (/ws) alive
@EnableWebSocket              // Enables the new raw binary stream for the ESP32
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    @Value("${websocket.allowed-origin:http://localhost:3000,http://localhost:5173,https://localhost:3000,https://localhost:5173,http://localhost:4173,https://localhost:4173,https://192.168.0.159:3000}")
    private String allowedOrigin;

    private final LivenessStreamHandler livenessStreamHandler;

    @Autowired
    public WebSocketConfig(LivenessStreamHandler livenessStreamHandler) {
        this.livenessStreamHandler = livenessStreamHandler;
    }

    // ==========================================
    // 1. EXISTING STOMP CONFIGURATION (Frontend)
    // ==========================================
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

    // ==========================================
    // 2. NEW RAW WEBSOCKET CONFIG (ESP32-CAM)
    // ==========================================
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The ESP32 gets a raw, high-speed binary channel.
        // We allow "*" so the hardware doesn't fail CORS checks.
        registry.addHandler(livenessStreamHandler, "/api/camera/stream")
                .setAllowedOrigins("*");
    }
}