package com.evoting.security;

import com.evoting.service.TerminalAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * TerminalAuthFilter — application-layer terminal identity for cloud deployments.
 *
 * Intercepts all /api/terminal/** requests and verifies:
 *   1. Required headers present: X-Terminal-Id, X-Request-Timestamp, X-Terminal-Signature
 *   2. Terminal is registered and active in terminal_registry
 *   3. Timestamp within ±5 minutes (replay protection)
 *   4. ECDSA P-256 signature valid for the canonical payload
 *
 * Exemptions (no signing required):
 *   - POST /api/terminal/heartbeat — not security-sensitive; signed separately
 *     via heartbeat payload but exempted here to reduce terminal firmware complexity
 *   - GET  /api/terminal/candidates — read-only, no side effects
 *   - GET  /api/terminal/pending_enrollment — read-only
 *
 * All other /api/terminal/** endpoints require a valid signature.
 *
 * Body caching:
 *   HttpServletRequest body is a stream — reading it in the filter would consume it,
 *   making it unavailable to the controller. We wrap the request in
 *   CachedBodyRequestWrapper to allow the body to be read multiple times.
 *
 * Render / cloud deployment note:
 *   Set TERMINAL_AUTH_ENABLED=true (default) in production.
 *   Set TERMINAL_AUTH_ENABLED=false only during local development when
 *   the firmware is not yet configured with signing.
 */
@Component
@Order(1)   // Before JWT filter and rate limiter
@Slf4j
public class TerminalAuthFilter extends OncePerRequestFilter {

    // Endpoints that don't require terminal signature
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/terminal/heartbeat",        // low-risk; exempted for simplicity
            "/api/terminal/candidates",       // read-only
            "/api/terminal/pending_enrollment" // read-only
    );

    @Autowired private TerminalAuthService terminalAuth;
    @Autowired private ObjectMapper        mapper;

    @Value("${terminal.auth.enabled:true}")
    private boolean enabled;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/terminal/")) return true;
        // Check exact path match for exemptions (ignore query string)
        return EXEMPT_PATHS.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws IOException, ServletException {

        // Wrap to allow body re-read
        CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);

        String terminalId = request.getHeader("X-Terminal-Id");
        String timestamp  = request.getHeader("X-Request-Timestamp");
        String signature  = request.getHeader("X-Terminal-Signature");

        if (isBlank(terminalId) || isBlank(timestamp) || isBlank(signature)) {
            sendError(response, 401, "MISSING_TERMINAL_AUTH",
                    "Terminal requests require X-Terminal-Id, X-Request-Timestamp, " +
                            "and X-Terminal-Signature headers. " +
                            "Register your terminal and update firmware.");
            return;
        }

        try {
            // Compute SHA-256 of raw body for canonical payload
            byte[] bodyBytes = wrapped.getCachedBody();
            String bodyHash  = base64Sha256(bodyBytes);

            terminalAuth.verify(terminalId, timestamp, bodyHash, signature);
            chain.doFilter(wrapped, response);  // pass WRAPPED request downstream

        } catch (SecurityException e) {
            log.warn("[TERMINAL-AUTH] Rejected {}: {}", terminalId, e.getMessage());
            sendError(response, 401, "TERMINAL_AUTH_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("[TERMINAL-AUTH] Unexpected error", e);
            sendError(response, 500, "TERMINAL_AUTH_ERROR", "Terminal authentication failed");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void sendError(HttpServletResponse res, int status,
                           String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        mapper.writeValue(res.getWriter(),
                Map.of("error", message, "code", code));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String base64Sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(md.digest(data));
    }

    // ── Inner class: cached body request wrapper ───────────────────────────

    /**
     * Wraps HttpServletRequest to allow the body InputStream to be read
     * multiple times (once in the filter for hashing, once in the controller).
     */
    static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() { return cachedBody; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                public int  read()                          { return bais.read(); }
                public boolean isFinished()                 { return bais.available() == 0; }
                public boolean isReady()                    { return true; }
                public void setReadListener(ReadListener l) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
