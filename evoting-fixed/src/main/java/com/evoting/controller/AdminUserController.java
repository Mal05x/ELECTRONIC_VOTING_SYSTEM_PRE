package com.evoting.controller;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import com.evoting.service.AdminUserService;
import com.evoting.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin User Management API — SUPER_ADMIN only.
 *
 *   GET  /api/admin/users              → list all admin accounts
 *   POST /api/admin/users              → create new admin account
 *   PUT  /api/admin/users/{id}/role    → change role
 *   PUT  /api/admin/users/{id}/deactivate → deactivate (soft-delete)
 *   PUT  /api/admin/users/{id}/activate   → reactivate
 */
@RestController
@RequestMapping("/api/admin/users")
@Slf4j
public class AdminUserController {

    @Autowired private AdminUserRepository adminRepo;
    @Autowired private AdminUserService    adminService;
    @Autowired private AuditLogService     auditLog;

    /** GET /api/admin/users — list all admin accounts (password hashes excluded by @JsonIgnore) */
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> users = adminRepo.findAll().stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          u.getId().toString());
                    m.put("username",    u.getUsername());
                    m.put("email",       u.getEmail() != null ? u.getEmail() : "");
                    m.put("role",        u.getRole().name());
                    m.put("active",      u.isActive());
                    m.put("lastLogin",   u.getLastLogin() != null ? u.getLastLogin().toString() : null);
                    m.put("createdAt",   u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /**
     * POST /api/admin/users
     * Body: { "username": "...", "password": "...", "role": "ADMIN|SUPER_ADMIN|OBSERVER" }
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> createUser(
            @RequestBody Map<String, String> body, Authentication auth) {
        String username = body.get("username");
        String password = body.get("password");
        String roleStr  = body.get("role");

        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        if (password == null || password.length() < 8)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "password must be at least 8 characters"));

        AdminUser.AdminRole role;
        try {
            role = AdminUser.AdminRole.valueOf(
                    roleStr != null ? roleStr.toUpperCase() : "OBSERVER");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "role must be SUPER_ADMIN, ADMIN, or OBSERVER"));
        }

        try {
            AdminUser created = adminService.createAdmin(username, password, role);
            auditLog.log("ADMIN_USER_CREATED", auth.getName(),
                    "NewUser=" + username + " role=" + role);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id",       created.getId().toString(),
                    "username", created.getUsername(),
                    "role",     created.getRole().name(),
                    "active",   created.isActive(),
                    "message",  "Admin account created successfully"
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/users/{id}/role
     * Body: { "role": "ADMIN|SUPER_ADMIN|OBSERVER" }
     */
    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> changeRole(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        AdminUser.AdminRole role;
        try {
            role = AdminUser.AdminRole.valueOf(body.get("role").toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid role. Use SUPER_ADMIN, ADMIN, or OBSERVER"));
        }
        return adminRepo.findById(id)
                .map(u -> {
                    u.setRole(role);
                    adminRepo.save(u);
                    auditLog.log("ADMIN_ROLE_CHANGED", auth.getName(),
                            "User=" + u.getUsername() + " newRole=" + role);
                    return ResponseEntity.ok(Map.of(
                            "username", u.getUsername(), "role", role.name()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** PUT /api/admin/users/{id}/deactivate */
    @PutMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> deactivate(
            @PathVariable UUID id, Authentication auth) {
        // Prevent self-deactivation
        AdminUser self = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElse(null);
        if (self != null && self.getId().equals(id))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "You cannot deactivate your own account"));

        return adminRepo.findById(id)
                .map(u -> {
                    u.setActive(false);
                    adminRepo.save(u);
                    auditLog.log("ADMIN_DEACTIVATED", auth.getName(), "User=" + u.getUsername());
                    return ResponseEntity.ok(Map.of(
                            "username", u.getUsername(), "active", false));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** PUT /api/admin/users/{id}/activate */
    @PutMapping("/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> activate(
            @PathVariable UUID id, Authentication auth) {
        return adminRepo.findById(id)
                .map(u -> {
                    u.setActive(true);
                    adminRepo.save(u);
                    auditLog.log("ADMIN_REACTIVATED", auth.getName(), "User=" + u.getUsername());
                    return ResponseEntity.ok(Map.of(
                            "username", u.getUsername(), "active", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
