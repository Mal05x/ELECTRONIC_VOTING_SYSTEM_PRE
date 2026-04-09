package com.evoting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JwtBlacklistService — Redis-backed JWT revocation store.
 *
 * On logout, the token's JTI is written to Redis with a TTL equal to
 * the token's remaining lifetime. The entry auto-expires — no cleanup needed.
 *
 * JwtAuthFilter checks this blacklist on every authenticated request.
 * If the JTI is present, the request is rejected with 401.
 *
 * Redis failure handling: if Redis is unreachable:
 *  - revoke(): logs warning, fails silently (token stays valid — acceptable
 *              tradeoff; alternative is blocking logout which is worse UX).
 *  - isRevoked(): returns false (fail-open) — logs warning so ops can detect.
 *
 * Key format:  jwt:revoked:<jti>
 * Value:       "1" (content irrelevant; presence = revoked)
 * TTL:         remaining token lifetime in seconds
 */
@Service
@Slf4j
public class JwtBlacklistService {

    private static final String KEY_PREFIX = "jwt:revoked:";

    @Autowired
    private StringRedisTemplate redis;

    /**
     * Revoke a token by storing its JTI in Redis.
     *
     * @param jti            The JWT ID from the token's jti claim.
     * @param remainingSecs  Seconds until the token naturally expires.
     */
    public void revoke(String jti, long remainingSecs) {
        if (jti == null || jti.isBlank() || remainingSecs <= 0) return;
        try {
            redis.opsForValue().set(
                    KEY_PREFIX + jti,
                    "1",
                    remainingSecs,
                    TimeUnit.SECONDS
            );
            log.debug("[JWT-BLACKLIST] Revoked jti={} TTL={}s", jti, remainingSecs);
        } catch (Exception e) {
            // Fail silently — token will expire naturally
            log.warn("[JWT-BLACKLIST] Redis unavailable — token not revoked, will expire in {}s: {}",
                    remainingSecs, e.getMessage());
        }
    }

    /**
     * Check if a token has been revoked.
     *
     * @param jti  The JWT ID to check.
     * @return     true if revoked (deny access), false if not revoked or Redis unavailable.
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            return redis.hasKey(KEY_PREFIX + jti);
        } catch (Exception e) {
            log.warn("[JWT-BLACKLIST] Redis unavailable for revocation check — failing open: {}", e.getMessage());
            return false; // fail-open: prefer availability over strict revocation during Redis outage
        }
    }
}
