package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.model.*;
import com.evoting.repository.AdminUserRepository;
import com.evoting.repository.TerminalHeartbeatRepository;
import com.evoting.security.JwtTokenProvider;
import com.evoting.service.*;
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

    @Autowired private AuthenticationService       authService;
    @Autowired private AdminUserService            adminService;
    @Autowired private AuditLogService             auditLog;
    @Autowired private JwtTokenProvider            jwt;
    @Autowired private TerminalHeartbeatRepository heartbeatRepo;
    @Autowired private AdminUserRepository         adminRepo;
    @Autowired private PasswordEncoder             passwordEncoder;

    /** POST /api/terminal/authenticate */
    /*@PostMapping("/terminal/authenticate")
    public ResponseEntity<SessionTokenDTO> authenticate(@RequestBody Map<String, String> body) throws Exception {
        return ResponseEntity.ok(authService.authenticate(body.get("payload")));
    }*/

    /**
     * POST /api/auth/login
     * Returns { token, role, username, email } so the frontend can
     * display the correct name and avatar colour immediately.
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

    /** POST /api/auth/logout — client-side token discard; server logs it */
    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, String>> logout(Authentication auth) {
        String actor = auth != null ? auth.getName() : "unknown";
        auditLog.log("ADMIN_LOGOUT", actor, "Session ended");
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
     * Body: { "email": "..." }
     * Records the request in the audit log. In production, wire to
     * an email service (SendGrid/SMTP). For now returns acknowledgement.
     */
    @PostMapping("/auth/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        // Log the request — email delivery wired separately
        auditLog.log("PASSWORD_RESET_REQUESTED", "SYSTEM", "Email: " + email);
        // Always return success so as not to reveal whether email exists
        return ResponseEntity.ok(Map.of(
                "message", "If that email is registered, a reset link has been sent."
        ));
    }

    /** POST /api/terminal/heartbeat */
    /*@PostMapping("/terminal/heartbeat")
    public ResponseEntity<Map<String, String>> heartbeat(@RequestBody HeartbeatDTO dto) {
        heartbeatRepo.save(TerminalHeartbeat.builder()
                .terminalId(dto.getTerminalId())
                .batteryLevel(dto.getBatteryLevel())
                .tamperFlag(dto.isTamperFlag())
                .ipAddress(dto.getIpAddress())
                .build());
        if (dto.isTamperFlag())
            auditLog.log("TERMINAL_TAMPER_ALERT", dto.getTerminalId(), "TAMPER FLAG SET");
        return ResponseEntity.ok(Map.of("status", "OK"));
    }*/
}
