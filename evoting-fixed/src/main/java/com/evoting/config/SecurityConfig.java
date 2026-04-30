package com.evoting.config;
import com.evoting.security.JwtAuthFilter;
import com.evoting.security.TerminalAuthFilter;
import com.evoting.security.StepUpAuthFilter;
import com.evoting.security.RateLimitFilter;
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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired private JwtAuthFilter    jwtFilter;
    @Autowired private TerminalAuthFilter terminalAuthFilter;
    @Autowired private RateLimitFilter rateLimitFilter;

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
                        // ESP32-CAM endpoints that require no JWT (authenticated by mTLS cert):
                        //   POST /api/camera/liveness  — frame submission from ESP32-CAM
                        //   GET  /api/camera/ping      — health check
                        // NOT permitted: PUT /api/camera/liveness-config — requires SUPER_ADMIN JWT
                        .requestMatchers("/api/camera/liveness").permitAll()
                        .requestMatchers("/api/camera/liveness-burst").permitAll()
                        .requestMatchers("/api/camera/ping").permitAll()
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
                .addFilterBefore(rateLimitFilter,     JwtAuthFilter.class)
                .addFilterBefore(terminalAuthFilter, RateLimitFilter.class);

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
    public FilterRegistrationBean<TerminalAuthFilter> terminalAuthFilterRegistration(TerminalAuthFilter filter) {
        FilterRegistrationBean<TerminalAuthFilter> reg = new FilterRegistrationBean<>(filter);
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
}
