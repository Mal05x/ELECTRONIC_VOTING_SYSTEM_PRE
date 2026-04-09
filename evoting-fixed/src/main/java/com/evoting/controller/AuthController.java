package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.model.*;
import com.evoting.repository.AdminUserRepository;
import com.evoting.repository.TerminalHeartbeatRepository;
import com.evoting.security.JwtTokenProvider;
import com.evoting.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    @Autowired private AuthenticationService   authService;
    @Autowired private AdminUserService        adminService;
    @Autowired private AuditLogService         auditLog;
    @Autowired private JwtTokenProvider        jwt;
    @Autowired private AdminUserRepository     adminRepo;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private PasswordResetService    passwordResetService;
    @Autowired private JwtBlacklistService     blacklistService;

    /**
     * POST /api/auth/login
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody @Valid LoginRequestDTO req) {
        AdminUser admin = adminService.authenticate(req.getUsername(), req.getPassword());
        String token = jwt.generateToken(admin.getUsername(), admin.getRole().name());
        auditLog.log("ADMIN_LOGIN", req.getUsername(), "Login successful");
        return ResponseEntity.ok(Map.of(
                "token",    token,
                "role",     admin.getRole().name(),
                "username", admin.getUsername(),
                "email",    admin.getEmail() != null ? admin.getEmail() : ""
        ));
    }

    /**
     * POST /api/auth/logout
     *
     * Revokes the current JWT by adding its JTI to the Redis blacklist
     * with TTL = remaining token lifetime. The token cannot be used again
     * even though it hasn't naturally expired yet.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, String>> logout(
            Authentication auth, HttpServletRequest request) {
        String actor = auth != null ? auth.getName() : "unknown";

        // Extract and revoke the token
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                String jti            = jwt.getJti(token);
                long   remainingSecs  = jwt.getRemainingSeconds(token);
                blacklistService.revoke(jti, remainingSecs);
            } catch (Exception e) {
                // Non-fatal — log and continue; token will expire naturally
            }
        }

        auditLog.log("ADMIN_LOGOUT", actor, "Session ended and token revoked");
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /**
     * PUT /api/auth/change-password
     * Body: { "currentPassword": "...", "newPassword": "..." }
     */
    @PutMapping("/auth/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String username = auth.getName();
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new RuntimeException("Admin not found"));
        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");
        if (currentPassword == null || newPassword == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "currentPassword and newPassword are required"));
        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash()))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Current password is incorrect"));
        if (newPassword.length() < 8)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "New password must be at least 8 characters"));
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        adminRepo.save(admin);
        auditLog.log("PASSWORD_CHANGED", username, "Admin changed their password");
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * POST /api/auth/forgot-password
     * Body: { "email": "admin@example.com" }
     *
     * Generates a one-time reset token and emails a reset link.
     * Always returns 200 to prevent email enumeration.
     */
    @PostMapping("/auth/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));

        // Fire-and-forget — never reveals whether email exists
        try {
            passwordResetService.generateAndSendResetToken(email.trim().toLowerCase());
        } catch (Exception e) {
            // Swallow — keep response identical regardless of outcome
        }

        return ResponseEntity.ok(Map.of(
                "message", "If that email is registered, a reset link has been sent."
        ));
    }

    /**
     * POST /api/auth/reset-password
     * Body: { "token": "...", "newPassword": "..." }
     *
     * Consumes the one-time token and resets the password.
     */
    @PostMapping("/auth/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @RequestBody Map<String, String> body) {
        String token       = body.get("token");
        String newPassword = body.get("newPassword");

        if (token == null || token.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        if (newPassword == null || newPassword.length() < 8)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "newPassword must be at least 8 characters"));

        try {
            passwordResetService.consumeTokenAndReset(token, newPassword);
            return ResponseEntity.ok(Map.of(
                    "message", "Password reset successfully. Please log in with your new password."));
        } catch (SecurityException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
