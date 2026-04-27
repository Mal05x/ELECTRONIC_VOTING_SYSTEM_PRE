package com.evoting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * LivenessServiceKeepAlive
 * ========================
 * Prevents Render's free tier from spinning down the Python liveness service.
 *
 * Problem:
 *   Render free tier kills any service that receives no inbound HTTP requests
 *   for 15 minutes. A cold start takes 8–12 seconds. The ESP32-CAM's HTTP
 *   timeout (HTTP_TIMEOUT_MS = 20000) means the first voter after any quiet
 *   period has a real chance of getting BURST_FAIL while the service restarts —
 *   blocking them at the terminal during an election.
 *
 * Solution:
 *   Spring Boot pings the Python service /health endpoint every 10 minutes.
 *   This is within Render's 15-minute window so the container stays warm.
 *
 * Why Spring Boot and not an external tool:
 *   UptimeRobot / BetterUptime can keep Spring Boot warm, but neither has
 *   direct access to the liveness service URL (it can be internal or behind
 *   Render's routing). Spring Boot is the only process that always has the
 *   correct liveness service URL configured.
 *
 * Recommended setup (belt AND braces):
 *   1. This class keeps the Python service warm from inside Spring Boot.
 *   2. Configure UptimeRobot (free) to ping Spring Boot's /actuator/health
 *      every 5 minutes — keeps Spring Boot itself warm so this class runs.
 *      UptimeRobot URL: https://uptimerobot.com (free account, 50 monitors)
 *
 * Disable:
 *   Set liveness.keepalive.enabled=false in application.yml to turn off.
 *   Useful in local dev where the Python service may not always be running.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "liveness.keepalive.enabled", havingValue = "true", matchIfMissing = true)
public class LivenessServiceKeepAlive {

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    private static final RestTemplate REST = new RestTemplate();

    /**
     * Fires every 10 minutes (600,000 ms).
     * fixedDelay means the next ping is 10 minutes AFTER the previous one
     * completes — so a slow cold-start response doesn't cause rapid-fire pings.
     * initialDelay 60s: gives Spring Boot time to fully start before first ping.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void pingLivenessService() {
        String url = livenessServiceUrl + "/health";
        try {
            var response = REST.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("[KeepAlive] Liveness service healthy at {}", livenessServiceUrl);
            } else {
                log.warn("[KeepAlive] Liveness service at {} returned {} — " +
                                "circuit breaker will trip if this persists.",
                        livenessServiceUrl, response.getStatusCode());
            }
        } catch (Exception e) {
            // Don't throw — a failed keep-alive ping is not a fatal error.
            // BiometricService's circuit breaker handles actual request failures.
            log.warn("[KeepAlive] Liveness service unreachable at {} — {}" +
                            " (this is expected during a cold start; will retry in 10 min)",
                    livenessServiceUrl, e.getMessage());
        }
    }
}
