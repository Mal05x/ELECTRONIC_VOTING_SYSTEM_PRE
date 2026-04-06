package com.evoting.security;

import com.evoting.repository.AdminUserRepository;
import com.evoting.model.AdminUser;
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

@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenProvider jwt;

    // Inject your specific repository
    @Autowired
    private AdminUserRepository adminUserRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwt.isValid(token)) {
                String username = jwt.getUsername(token);

                // ZERO-TRUST CHECK: Use your exact repository method
                // This only returns a user if they exist AND are currently active
                Optional<AdminUser> activeAdmin = adminUserRepository.findByUsernameAndActiveTrue(username);

                // If it's empty, they were either deleted or deactivated. Kick them out instantly.
                if (activeAdmin.isEmpty()) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\": \"Session invalid or account deactivated\"}");
                    return; // Stop the request completely
                }

                // They are active! Set the security context.
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getRole(token))));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}
