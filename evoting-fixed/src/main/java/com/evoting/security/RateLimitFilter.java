package com.evoting.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP rate limiter on /api/terminal/* endpoints — 1 request per 30 seconds.
 *
 * Fix B-17: Moved rate limit state from JVM-local ConcurrentHashMap to Redis.
 *
 * BUG: The previous ConcurrentHashMap implementation had three problems:
 *  1. Multi-pod deployments — each pod had its own bucket map; an attacker
 *     could hit 1 req/30s per pod (not per IP globally).
 *  2. Memory leak — the map grew without bound as new IPs connected.
 *  3. Restart bypass — on server restart all buckets reset.
 *
 * FIX: Redis atomic INCR + EXPIRE.
 *  - INCR creates the key (count=1) atomically on first request.
 *  - EXPIRE(30s) is set only on key creation — any subsequent request within
 *    the window sees count > 1 and is rejected.
 *  - Redis TTL provides automatic eviction — no memory leak.
 *  - Redis is shared across all pods — global per-IP limiting.
 *
 * Redis failure handling: if Redis is unavailable, the filter falls back to
 * allow-all to prevent the rate limiter from taking down voting during an outage.
 * This is logged at WARN level for monitoring.
 */
@Component
@Order(1)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int WINDOW_SECONDS = 30;
    private static final String KEY_PREFIX  = "ratelimit:";

    @Autowired
    private StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        if (!req.getRequestURI().startsWith("/api/terminal")) {
            chain.doFilter(req, res);
            return;
        }

        String clientIp = getClientIp(req);

        try {
            String key   = KEY_PREFIX + clientIp;
            Long   count = redis.opsForValue().increment(key);

            if (count != null && count == 1L) {
                // First request in the window — set the TTL
                redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            if (count != null && count > 1L) {
                // Rate limit exceeded
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write(
                    "{\"error\":\"Rate limit exceeded. One request per 30 seconds.\"}");
                return;
            }

        } catch (Exception redisEx) {
            // Fix B-17: Redis unavailable — log and allow the request through.
            // Failing closed (blocking all requests) during a Redis outage would
            // prevent voting, which is worse than temporarily relaxed rate limiting.
            log.warn("Rate limit Redis unavailable — allowing request through: {}",
                redisEx.getMessage());
        }

        chain.doFilter(req, res);
    }

    private String getClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first IP in the chain (the original client IP)
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
