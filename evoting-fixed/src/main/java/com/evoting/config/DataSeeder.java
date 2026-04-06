package com.evoting.config;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder {

    @Bean
    public CommandLineRunner initDatabase(AdminUserRepository adminRepo, PasswordEncoder passwordEncoder) {
        return args -> {
            // Only create the user if the table is completely empty
            if (adminRepo.count() == 0) {

                AdminUser defaultAdmin = AdminUser.builder()
                        .username("admin")
                        .passwordHash(passwordEncoder.encode("password")) // Matches your exact field name!
                        .role(AdminUser.AdminRole.SUPER_ADMIN)            // Matches your exact Enum!
                        .active(true)
                        .displayName("System Admin")
                        .build();

                adminRepo.save(defaultAdmin);

                System.out.println("=================================================");
                System.out.println(" Default SUPER_ADMIN created! ");
                System.out.println(" Username: admin ");
                System.out.println(" Password: password ");
                System.out.println("=================================================");
            }
        };
    }
}