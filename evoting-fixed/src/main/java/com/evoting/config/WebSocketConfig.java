package com.evoting.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Comma-separated list of allowed WebSocket origins.
     * Default covers all common local dev ports on http and https.
     * In production set WEBSOCKET_ALLOWED_ORIGIN=https://your-frontend.vercel.app
     */
    @Value("${websocket.allowed-origin:http://localhost:3000,http://localhost:5173,https://localhost:3000,https://localhost:5173,http://localhost:4173,https://localhost:4173,https://192.168.0.159:3000}")
    private String allowedOrigin;

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
}
