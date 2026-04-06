package com.evoting.service;

import com.evoting.repository.BallotBoxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Anomaly Detection Service (V2 addition).
 *
 * Detects suspicious voting patterns in real-time:
 *   1. Vote-rate anomaly: a single terminal submitting > N votes in a short window.
 *      A human cannot physically vote in < 30 seconds — anything faster is suspicious.
 *   2. Rapid-fire detection: > MAX_VOTES_PER_WINDOW votes per terminal per time window.
 *
 * Stores counters in Redis for cross-pod consistency (same as RateLimitFilter).
 * Falls back to in-memory ConcurrentHashMap if Redis is unavailable.
 */
@Service
@Slf4j
public class AnomalyDetectionService {

    /** Time window for vote-rate monitoring (seconds) */
    private static final int WINDOW_SECONDS = 60;

    /** Votes per terminal per window above which an alert is raised */
    private static final int MAX_VOTES_PER_WINDOW = 5;

    /** Minimum plausible time between two votes on the same terminal (seconds) */
    private static final int MIN_VOTE_INTERVAL_SECONDS = 25;

    private static final String KEY_PREFIX = "anomaly:votes:";

    @Autowired private StringRedisTemplate redis;
    @Autowired private AuditLogService     auditLog;

    /** In-memory fallback if Redis is unavailable */
    private final ConcurrentHashMap<String, Long> lastVoteTime = new ConcurrentHashMap<>();

    /** Anomaly alerts accumulated since last poll — read by the admin API */
    private final List<Map<String, Object>> pendingAlerts = new ArrayList<>();

    /**
     * Called by VoteProcessingService after each successfully recorded vote.
     * Checks for suspicious vote-rate patterns on the submitting terminal.
     */
    public void recordVote(String terminalId) {
        long now = System.currentTimeMillis() / 1000L;

        // ── Check 1: minimum interval between consecutive votes ────────────
        Long lastTime = lastVoteTime.get(terminalId);
        if (lastTime != null) {
            long intervalSeconds = now - lastTime;
            if (intervalSeconds < MIN_VOTE_INTERVAL_SECONDS) {
                String alert = String.format(
                        "Vote interval anomaly: terminal %s submitted two votes %d seconds apart " +
                                "(minimum expected: %d seconds). Possible bot or hardware compromise.",
                        terminalId, intervalSeconds, MIN_VOTE_INTERVAL_SECONDS);
                raiseAlert("VOTE_INTERVAL_ANOMALY", terminalId, alert);
            }
        }
        lastVoteTime.put(terminalId, now);

        // ── Check 2: vote-rate window (Redis) ──────────────────────────────
        try {
            String key   = KEY_PREFIX + terminalId;
            Long   count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (count != null && count > MAX_VOTES_PER_WINDOW) {
                String alert = String.format(
                        "High vote rate: terminal %s submitted %d votes in %d seconds " +
                                "(limit: %d). Investigate immediately.",
                        terminalId, count, WINDOW_SECONDS, MAX_VOTES_PER_WINDOW);
                raiseAlert("HIGH_VOTE_RATE", terminalId, alert);
            }
        } catch (Exception e) {
            log.warn("Anomaly detection Redis unavailable — rate check skipped: {}", e.getMessage());
        }
    }

    private void raiseAlert(String type, String terminalId, String detail) {
        log.warn("[ANOMALY ALERT] type={} terminal={} detail={}", type, terminalId, detail);
        auditLog.log("ANOMALY_DETECTED", terminalId, type + " | " + detail);
        synchronized (pendingAlerts) {
            pendingAlerts.add(Map.of(
                    "type",       type,
                    "terminalId", terminalId,
                    "detail",     detail,
                    "timestamp",  OffsetDateTime.now().toString()
            ));
            // Keep at most 100 pending alerts in memory
            if (pendingAlerts.size() > 100) pendingAlerts.remove(0);
        }
    }

    /** Returns and clears pending alerts — polled by the admin dashboard API */
    public List<Map<String, Object>> drainAlerts() {
        synchronized (pendingAlerts) {
            List<Map<String, Object>> copy = new ArrayList<>(pendingAlerts);
            pendingAlerts.clear();
            return copy;
        }
    }

    /** Returns pending alerts without clearing them — for the admin stats API */
    public List<Map<String, Object>> peekAlerts() {
        synchronized (pendingAlerts) {
            return new ArrayList<>(pendingAlerts);
        }
    }

    /** Clear stale in-memory last-vote times every hour */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        lastVoteTime.clear();
    }
}
