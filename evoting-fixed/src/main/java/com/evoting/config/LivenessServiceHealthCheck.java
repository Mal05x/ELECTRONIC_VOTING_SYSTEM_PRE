package com.evoting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Checks that the Python liveness microservice is reachable on startup.
 * Logs a clear WARNING (does not crash) so the application starts normally.
 * The BiometricService fail-open policy handles unavailability at runtime.
 */
@Component
@Slf4j
public class LivenessServiceHealthCheck {

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void checkLivenessService() {
        String healthUrl = livenessServiceUrl + "/health";
        try {
            RestTemplate rt = new RestTemplate();
            var response = rt.getForEntity(healthUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✓ Liveness service reachable at {} — MiniFASNet models loaded.",
                        livenessServiceUrl);
            } else {
                log.warn("⚠ Liveness service at {} returned HTTP {} — " +
                                "liveness evaluation may fall back to basic JPEG validation.",
                        livenessServiceUrl, response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("⚠ Liveness service NOT reachable at {} ({}). " +
                            "Start it with: cd liveness-service && docker-compose up -d\n" +
                            "Liveness will fall back to basic JPEG validation until it is running.",
                    livenessServiceUrl, e.getMessage());
        }
    }
}
