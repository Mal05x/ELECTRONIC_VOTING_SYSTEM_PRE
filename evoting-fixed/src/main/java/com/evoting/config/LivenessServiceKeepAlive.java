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
 * Prevents Render's free tier from spinning down the Python liveness
 * services — both the passive MiniFASNet service (port 5001) and the
 * active MediaPipe service (port 5002).
 *
 * Problem:
 *   Render free tier kills any service that receives no inbound HTTP requests
 *   for 15 minutes. A cold start takes 8–12 seconds. The ESP32-CAM's HTTP
 *   timeout (HTTP_TIMEOUT_MS = 20000) means the first voter after any quiet
 *   period has a real chance of getting BURST_FAIL while the service restarts —
 *   blocking them at the terminal during an election. The active service has
 *   the same exposure: a browser admin (or terminal, in ACTIVE mode) hitting
 *   a cold active/app.py eats an 8–12s stall on /analyze-frame.
 *
 * Solution:
 *   Spring Boot pings each Python service's /health endpoint every 10
 *   minutes. This is within Render's 15-minute window so both containers
 *   stay warm. Only whichever service is actually in use as the terminal's
 *   liveness mode strictly needs to stay warm, but pinging both is cheap
 *   and avoids a cold-start surprise if an admin switches liveness.mode
 *   (PASSIVE ↔ ACTIVE) at runtime without remembering to warm the other one.
 *
 * Why Spring Boot and not an external tool:
 *   UptimeRobot / BetterUptime can keep Spring Boot warm, but neither has
 *   direct access to the liveness service URLs (they can be internal or
 *   behind Render's routing). Spring Boot is the only process that always
 *   has the correct liveness service URLs configured.
 *
 * Recommended setup (belt AND braces):
 *   1. This class keeps both Python services warm from inside Spring Boot.
 *   2. Configure UptimeRobot (free) to ping Spring Boot's /actuator/health
 *      every 5 minutes — keeps Spring Boot itself warm so this class runs.
 *      UptimeRobot URL: https://uptimerobot.com (free account, 50 monitors)
 *
 * Disable:
 *   Set liveness.keepalive.enabled=false in application.yml to turn off
 *   both pings. Useful in local dev where the Python services may not
 *   always be running.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "liveness.keepalive.enabled", havingValue = "true", matchIfMissing = true)
public class LivenessServiceKeepAlive {

    @Value("${liveness.service.url:http://127.0.0.1:5001}")
    private String livenessServiceUrl;

    // Same property key BiometricService already reads this from — see the
    // matching fix in BiometricController for why the key matters (a
    // mismatched key here would mean this "keep-alive" pings the wrong URL,
    // or the hardcoded default, and never actually warms the real service).
    @Value("${liveness.active-service.url:http://127.0.0.1:5002}")
    private String activeServiceUrl;

    private static final RestTemplate REST = new RestTemplate();

    /**
     * Fires every 10 minutes (600,000 ms).
     * fixedDelay means the next ping is 10 minutes AFTER the previous one
     * completes — so a slow cold-start response doesn't cause rapid-fire pings.
     * initialDelay 60s: gives Spring Boot time to fully start before first ping.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void pingPassiveLivenessService() {
        ping("passive", livenessServiceUrl);
    }

    /**
     * Same schedule as the passive ping, offset by 5s (initialDelay 65_000)
     * purely so the two don't fire in the exact same tick — no functional
     * reason beyond slightly cleaner logs.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 65_000)
    public void pingActiveLivenessService() {
        ping("active", activeServiceUrl);
    }

    private void ping(String label, String baseUrl) {
        String url = baseUrl + "/health";
        try {
            var response = REST.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("[KeepAlive] {} liveness service healthy at {}", label, baseUrl);
            } else {
                log.warn("[KeepAlive] {} liveness service at {} returned {} — " +
                                "circuit breaker will trip if this persists.",
                        label, baseUrl, response.getStatusCode());
            }
        } catch (Exception e) {
            // Don't throw — a failed keep-alive ping is not a fatal error.
            // BiometricService's circuit breaker handles actual request failures.
            log.warn("[KeepAlive] {} liveness service unreachable at {} — {}" +
                            " (this is expected during a cold start; will retry in 10 min)",
                    label, baseUrl, e.getMessage());
        }
    }
}
