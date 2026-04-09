package com.evoting.security;

import com.evoting.repository.AdminUserRepository;
import com.evoting.model.AdminUser;
import com.evoting.service.JwtBlacklistService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * JWT authentication filter with:
 *   1. Token signature + expiry validation (existing)
 *   2. Active admin check — deactivated accounts rejected immediately (existing)
 *   3. JWT blacklist check — revoked tokens (logged-out) rejected (NEW)
 */
@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired private JwtTokenProvider     jwt;
    @Autowired private AdminUserRepository  adminUserRepository;
    @Autowired private JwtBlacklistService  blacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwt.isValid(token)) {

                // ── Blacklist check (revoked on logout) ───────────────────
                String jti = jwt.getJti(token);
                if (blacklist.isRevoked(jti)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\": \"Session has been revoked. Please log in again.\"}");
                    return;
                }

                String username = jwt.getUsername(token);

                // ── Zero-trust: only active admins ────────────────────────
                Optional<AdminUser> activeAdmin = adminUserRepository.findByUsernameAndActiveTrue(username);
                if (activeAdmin.isEmpty()) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\": \"Session invalid or account deactivated\"}");
                    return;
                }

                var auth = new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getRole(token))));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}
