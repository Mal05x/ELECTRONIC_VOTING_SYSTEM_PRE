package com.evoting.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Global API Rate Limiter
 * 
 * - Terminal limits: 5 requests per 30s. Accommodates the ESP32 background 
 *   sync flow which requires consecutive POSTs to /tap and /vote. Fails OPEN 
 *   if Redis goes down to keep elections running.
 * - Admin limits: 5 attempts per 5m. Fails CLOSED if Redis goes down to 
 *   prevent DoS brute-force bypasses.
 */
@Component
@Order(1)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    // ── Terminal Endpoints (Voting, Heartbeat, Sync) ──────────────────
    private static final int TERMINAL_WINDOW_SECS = 30;
    // Set to 5 to allow the hardware's 1-sec staggered Tap + Vote + Retries
    private static final int TERMINAL_MAX_REQUESTS = 5; 
    private static final String TERMINAL_KEY_PREFIX = "ratelimit:terminal:";

    // ── Admin Login (Brute Force Protection) ──────────────────────────
    private static final int LOGIN_WINDOW_SECS = 300; // 5 minutes
    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final String LOGIN_KEY_PREFIX = "ratelimit:login:";

    // Atomic INCR + EXPIRE Lua script. Prevents the permanent-lockout race condition
    // if a thread dies between incrementing and setting the TTL.
    private static final String LUA_SCRIPT = 
        "local current = redis.call('INCR', KEYS[1]) " +
        "if current == 1 then " +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
        "end " +
        "return current";

    @Autowired
    private StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String uri = req.getRequestURI();
        
        // Relies on Spring Boot's 'server.forward-headers-strategy=framework'
        // to securely resolve the real IP from the load balancer/proxy.
        String clientIp = req.getRemoteAddr(); 

        // ── Admin Login ────────────────────────────────────────────────
        if (uri.equals("/api/auth/login") && "POST".equalsIgnoreCase(req.getMethod())) {
            // Fail-closed (true) on Redis error for security
            if (!checkRateLimit(res, LOGIN_KEY_PREFIX + clientIp, LOGIN_WINDOW_SECS, LOGIN_MAX_ATTEMPTS, 
                    "Too many login attempts. Please wait 5 minutes.", true)) {
                return;
            }
        } 
        // ── Terminal Endpoints ─────────────────────────────────────────
        else if (uri.startsWith("/api/terminal")) {
            // Fail-open (false) on Redis error to preserve voting availability
            if (!checkRateLimit(res, TERMINAL_KEY_PREFIX + clientIp, TERMINAL_WINDOW_SECS, TERMINAL_MAX_REQUESTS, 
                    "Terminal rate limit exceeded. Please wait.", false)) {
                return;
            }
        }

        chain.doFilter(req, res);
    }

    /**
     * Executes the rate limit check against Redis.
     * @return true if request is allowed, false if limit exceeded.
     */
    private boolean checkRateLimit(HttpServletResponse res, String key, int windowSecs, 
                                   int maxRequests, String message, boolean failClosed) throws IOException {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
            Long count = redis.execute(script, Collections.singletonList(key), String.valueOf(windowSecs));

            if (count != null && count > maxRequests) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"" + message + "\"}");
                log.warn("[RATE-LIMIT] Blocked key={} count={}", key, count);
                return false;
            }
        } catch (Exception redisEx) {
            if (failClosed) {
                log.error("[RATE-LIMIT] Redis unavailable — blocking admin login request for safety.");
                res.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return false;
            } else {
                log.warn("[RATE-LIMIT] Redis unavailable — allowing terminal request: {}", redisEx.getMessage());
            }
        }
        return true;
    }
}
