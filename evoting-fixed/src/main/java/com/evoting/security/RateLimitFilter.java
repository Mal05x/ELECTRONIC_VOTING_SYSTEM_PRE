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

    // Terminal endpoints — 1 request per 30 seconds per IP
    private static final int    TERMINAL_WINDOW_SECS = 30;
    private static final int    TERMINAL_MAX_REQUESTS = 1;
    private static final String TERMINAL_KEY_PREFIX  = "ratelimit:terminal:";

    // Admin login — 5 attempts per 5 minutes per IP (brute-force protection)
    private static final int    LOGIN_WINDOW_SECS    = 300;   // 5 minutes
    private static final int    LOGIN_MAX_ATTEMPTS   = 5;
    private static final String LOGIN_KEY_PREFIX     = "ratelimit:login:";

    @Autowired
    private StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String uri      = req.getRequestURI();
        String clientIp = getClientIp(req);

        // ── Admin login brute-force protection ────────────────────────────
        if (uri.equals("/api/auth/login") && "POST".equalsIgnoreCase(req.getMethod())) {
            if (!checkRateLimit(res, LOGIN_KEY_PREFIX + clientIp,
                    LOGIN_WINDOW_SECS, LOGIN_MAX_ATTEMPTS,
                    "Too many login attempts. Please wait 5 minutes before trying again.")) {
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // ── Terminal endpoint rate limiting ───────────────────────────────
        if (!uri.startsWith("/api/terminal")) {
            chain.doFilter(req, res);
            return;
        }

        if (!checkRateLimit(res, TERMINAL_KEY_PREFIX + clientIp,
                TERMINAL_WINDOW_SECS, TERMINAL_MAX_REQUESTS,
                "Rate limit exceeded. One request per 30 seconds.")) {
            return;
        }

        chain.doFilter(req, res);
    }

    /**
     * Check the rate limit for a given Redis key.
     * @return true if request is allowed, false if limit exceeded (response already written)
     */
    private boolean checkRateLimit(HttpServletResponse res, String key,
                                   int windowSecs, int maxRequests, String message)
            throws IOException {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, windowSecs, TimeUnit.SECONDS);
            }
            if (count != null && count > maxRequests) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"" + message + "\"}");
                log.warn("[RATE-LIMIT] Blocked key={} count={}", key, count);
                return false;
            }
        } catch (Exception redisEx) {
            log.warn("[RATE-LIMIT] Redis unavailable — allowing request: {}", redisEx.getMessage());
        }
        return true;
    }

    private String getClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
