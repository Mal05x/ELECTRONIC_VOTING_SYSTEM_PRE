package com.evoting.security;
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

/**
 * JWT validation filter for admin endpoints.
 *
 * Fix 7: @Order(2) ensures this always executes AFTER RateLimitFilter (@Order(1)).
 * The ordering is explicit and deterministic regardless of Spring's bean
 * registration order.
 */
@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired private JwtTokenProvider jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwt.isValid(token)) {
                var auth = new UsernamePasswordAuthenticationToken(
                    jwt.getUsername(token), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getRole(token))));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}
