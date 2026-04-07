package com.evoting.config;
import com.evoting.security.JwtAuthFilter;
import com.evoting.security.StepUpAuthFilter;
import com.evoting.security.RateLimitFilter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;
import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import com.evoting.security.JwtTokenProvider;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired private JwtAuthFilter   jwtFilter;
    @Autowired private RateLimitFilter rateLimitFilter;
    @Autowired
    private AdminUserRepository adminRepo;

    @Autowired
    private JwtTokenProvider jwtProvider; // Again, rename this if your class is called JwtTokenProvider

    /**
     * CORS allowed origins — comma-separated list.
     * Value comes from application.yml which reads the CORS_ALLOWED_ORIGIN env var.
     * Default covers all common local dev ports on both http and https.
     */
    @Value("${security.cors.allowed-origin:http://localhost:3000,http://localhost:5173,https://localhost:3000,https://localhost:5173,https://192.168.0.159:3000}")
    private String corsAllowedOrigin;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .oauth2Login(oauth -> oauth
                        .successHandler(oauthSuccessHandler())
                )
                /*
                 * Return 401 (not 403) for unauthenticated requests to protected endpoints.
                 * Without this, Spring Security 6 defaults to 403 for any rejected request,
                 * which confuses the frontend — 403 looks like an auth success followed by
                 * an access-denied, making it indistinguishable from a real permission error.
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )

                .authorizeHttpRequests(auth -> auth
                        /*
                         * Permit all CORS preflight OPTIONS requests unconditionally.
                         * Without this, Spring Security 6 applies full auth chain to OPTIONS,
                         * returning 403 before CORS headers are ever written — the browser then
                         * reports the actual POST as a network/CORS error.
                         */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/api/terminal/**").permitAll()
                        .requestMatchers("/api/locations/**").permitAll()
                        .requestMatchers("/api/results/**").permitAll()
                        .requestMatchers("/api/receipt/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/forgot-password").permitAll()
                        .requestMatchers("/api/camera/**").permitAll()         // ESP32-CAM liveness stream (mTLS auth)
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )

                /*
                 * Register filters inside the security chain ONLY.
                 * The @Component annotation on these filters would cause Spring Boot to
                 * also register them as regular servlet filters (running before Spring Security).
                 * We prevent that double-registration via FilterRegistrationBean beans below.
                 * Only adding them here ensures correct, single-pass ordering:
                 *   RateLimitFilter → JwtAuthFilter → Spring Security auth
                 */
                .addFilterBefore(jwtFilter,       UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthFilter.class);

        return http.build();
    }

    /**
     * Prevent JwtAuthFilter from being auto-registered as a servlet filter by Spring Boot.
     * Without this, it runs TWICE — once before Spring Security (as servlet filter)
     * and once inside the security chain (via addFilterBefore above).
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * Same prevention for RateLimitFilter.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();

        // Split comma-separated list — supports multiple origins (dev + prod simultaneously)
        List<String> origins = java.util.Arrays.stream(corsAllowedOrigin.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toList());

        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
    @Bean
    public AuthenticationSuccessHandler oauthSuccessHandler() {
        return (request, response, authentication) -> {
            OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
            String email = oidcUser.getEmail();

            // Find existing admin, or auto-create as OBSERVER
            AdminUser admin = adminRepo.findByEmailIgnoreCase(email)
                    .orElseGet(() -> {
                        AdminUser newAdmin = AdminUser.builder()
                                .username(email)
                                .email(email)
                                .role(AdminUser.AdminRole.OBSERVER)
                                .active(true)
                                .build();
                        return adminRepo.save(newAdmin);
                    });

            // Generate your standard JWT token
            String token = jwtProvider.generateToken(admin.getUsername(), admin.getRole().name());

            // Redirect to your Vercel frontend, passing the token in the URL
            response.sendRedirect("https://electronic-voting-system-pre.vercel.app/oauth-callback?token=" + token
                    + "&role=" + admin.getRole().name()
                    + "&username=" + admin.getUsername());
        };
    }
}
