package com.evoting.service;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Service
public class AdminUserService {

    @Autowired private AdminUserRepository adminRepo;
    @Autowired private PasswordEncoder     passwordEncoder;

    @Transactional
    public AdminUser authenticate(String username, String password) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(username)
            .orElseThrow(() -> new EvotingAuthException("Invalid credentials"));
        if (!passwordEncoder.matches(password, admin.getPasswordHash()))
            throw new EvotingAuthException("Invalid credentials");
        admin.setLastLogin(OffsetDateTime.now());
        return adminRepo.save(admin);
    }

    @Transactional
    public AdminUser createAdmin(String username, String rawPassword, AdminUser.AdminRole role) {
        if (adminRepo.findByUsernameAndActiveTrue(username).isPresent())
            throw new RuntimeException("Username already exists");
        return adminRepo.save(AdminUser.builder()
            .username(username)
            .passwordHash(passwordEncoder.encode(rawPassword))
            .role(role).active(true).build());
    }
}
