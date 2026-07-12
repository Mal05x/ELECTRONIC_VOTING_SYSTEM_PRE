package com.evoting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Checks that the Python liveness microservices are reachable on startup —
 * both passive (MiniFASNet, port 5001) and active (MediaPipe, port 5002).
 * Logs a clear WARNING (does not crash) so the application starts normally.
 * The BiometricService fail-open policy handles unavailability at runtime.
 */
@Component
@Slf4j
public class LivenessServiceHealthCheck {

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    @Value("${liveness.active-service.url:http://127.0.0.1:5002}")
    private String activeServiceUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void checkLivenessService() {
        checkOne("Passive", livenessServiceUrl,
                "MiniFASNet models loaded.",
                "cd liveness-service && docker-compose up -d",
                "Liveness will fall back to basic JPEG validation until it is running.");
        checkOne("Active", activeServiceUrl,
                "MediaPipe challenge-response ready.",
                "cd active && python app.py",
                "Switching liveness.mode to ACTIVE will fail until it is running.");
    }

    private void checkOne(String label, String baseUrl, String okDetail,
                           String startHint, String downImpact) {
        String healthUrl = baseUrl + "/health";
        try {
            RestTemplate rt = new RestTemplate();
            var response = rt.getForEntity(healthUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✓ {} liveness service reachable at {} — {}", label, baseUrl, okDetail);
            } else {
                log.warn("⚠ {} liveness service at {} returned HTTP {} — {}",
                        label, baseUrl, response.getStatusCode(), downImpact);
            }
        } catch (Exception e) {
            log.warn("⚠ {} liveness service NOT reachable at {} ({}). Start it with: {}\n{}",
                    label, baseUrl, e.getMessage(), startHint, downImpact);
        }
    }
}
