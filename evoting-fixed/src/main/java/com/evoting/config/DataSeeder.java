package com.evoting.config;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * DataSeeder — seeds a default SUPER_ADMIN account on first boot.
 *
 * PRODUCTION GUARD: Only runs when SPRING_PROFILES_ACTIVE contains "dev"
 * OR when the ADMIN_SEED_PASSWORD env var is explicitly set.
 *
 * In production, set ADMIN_SEED_PASSWORD to a strong random password.
 * The default "password" account will NEVER be created in production
 * unless explicitly configured.
 *
 * Generate a seed password:
 *   openssl rand -base64 24
 */
@Configuration
@Slf4j
public class DataSeeder {

    @Value("${spring.profiles.active:prod}")
    private String activeProfiles;

    /**
     * Password for the seeded admin. In prod, set ADMIN_SEED_PASSWORD.
     * If unset in prod, seeding is skipped entirely.
     */
    @Value("${ADMIN_SEED_PASSWORD:}")
    private String seedPassword;

    @Bean
    public CommandLineRunner initDatabase(AdminUserRepository adminRepo,
                                          PasswordEncoder passwordEncoder) {
        return args -> {
            if (adminRepo.count() > 0) return; // DB already has accounts — skip

            boolean isDev  = activeProfiles.contains("dev");
            boolean hasSeedPw = seedPassword != null && !seedPassword.isBlank();

            if (!isDev && !hasSeedPw) {
                log.warn("=================================================");
                log.warn(" PRODUCTION MODE: No admin account seeded.");
                log.warn(" Set ADMIN_SEED_PASSWORD env var to create the");
                log.warn(" first SUPER_ADMIN account automatically, or");
                log.warn(" insert it manually via psql.");
                log.warn("=================================================");
                return;
            }

            String password = hasSeedPw ? seedPassword : "password";
            if (!hasSeedPw) {
                log.warn("=================================================");
                log.warn(" DEV MODE: Default admin/password created.");
                log.warn(" DO NOT deploy this to production.");
                log.warn("=================================================");
            }

            AdminUser defaultAdmin = AdminUser.builder()
                    .username("admin")
                    .passwordHash(passwordEncoder.encode(password))
                    .role(AdminUser.AdminRole.SUPER_ADMIN)
                    .active(true)
                    .displayName("System Admin")
                    .build();

            adminRepo.save(defaultAdmin);

            log.info("=================================================");
            log.info(" SUPER_ADMIN created: username=admin");
            log.info(" Profile: {}  SeedPwSet: {}", activeProfiles, hasSeedPw);
            log.info("=================================================");
        };
    }
}
