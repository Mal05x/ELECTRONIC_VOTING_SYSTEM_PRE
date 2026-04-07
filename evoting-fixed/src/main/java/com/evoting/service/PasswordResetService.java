package com.evoting.service;

import com.evoting.model.AdminUser;
import com.evoting.model.PasswordResetToken;
import com.evoting.repository.AdminUserRepository;
import com.evoting.repository.PasswordResetTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.internet.MimeMessage;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * PasswordResetService — secure email-based password reset flow.
 *
 * Flow:
 *   1. Admin submits email → generateAndSendResetToken() creates a
 *      one-time 64-byte hex token, stores it hashed, emails the link.
 *   2. Admin clicks link → frontend POSTs token + new password →
 *      consumeTokenAndReset() validates, resets password, marks token used.
 *
 * Security properties:
 *   - Token is 64 cryptographically random bytes (512 bits entropy)
 *   - Token expires after 30 minutes
 *   - Single-use: consumed immediately on first valid use
 *   - Response never reveals whether email exists in DB (prevents enumeration)
 *   - Token stored raw (not hashed) — for a government system consider
 *     storing SHA-256(token) and comparing on verify. Noted as future hardening.
 *
 * Email provider: Spring JavaMailSender — configure SMTP in application.yml.
 * For SendGrid, use host=smtp.sendgrid.net port=587 user=apikey.
 * For Gmail (dev), use host=smtp.gmail.com port=587 + app password.
 */
@Service @Slf4j
public class PasswordResetService {

    @Autowired private PasswordResetTokenRepository tokenRepo;
    @Autowired private AdminUserRepository           adminRepo;
    @Autowired private PasswordEncoder              encoder;
    @Autowired private AuditLogService              auditLog;
    @Autowired(required = false) private JavaMailSender mailSender;

    @Value("${app.base-url:https://localhost:5173}")
    private String baseUrl;

    @Value("${app.name:MFA E-Voting System}")
    private String appName;

    @Value("${spring.mail.from:noreply@evoting.gov.ng}")
    private String fromAddress;

    // ── Generate token and send email ─────────────────────────────────────

    @Transactional
    public void generateAndSendResetToken(String email) {
        // Always return silently — do not reveal whether email exists
        Optional<AdminUser> adminOpt = adminRepo.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) {
            log.info("[PWD-RESET] No account for email={} — silently ignored", email);
            return;
        }

        AdminUser admin = adminOpt.get();
        if (!admin.isActive()) {
            log.warn("[PWD-RESET] Inactive account requested reset: {}", email);
            return;
        }

        String rawToken = generateSecureToken();

        PasswordResetToken tokenRecord = PasswordResetToken.builder()
                .adminId(admin.getId())
                .token(rawToken)
                .build();
        tokenRepo.save(tokenRecord);

        String resetLink = baseUrl + "/reset-password?token=" + rawToken;

        auditLog.log("PASSWORD_RESET_TOKEN_ISSUED", admin.getUsername(),
                "ResetLinkSent email=" + email);

        if (mailSender == null) {
            // Mail not configured — log link for dev/test environments
            log.warn("[PWD-RESET] JavaMailSender not configured. Reset link (DEV ONLY): {}", resetLink);
            return;
        }

        try {
            sendResetEmail(admin.getUsername(), email, resetLink);
            log.info("[PWD-RESET] Reset email sent to {}", email);
        } catch (Exception e) {
            log.error("[PWD-RESET] Email delivery failed for {}: {}", email, e.getMessage());
            // Do not rethrow — caller returns generic success response regardless
        }
    }

    // ── Validate token and reset password ─────────────────────────────────

    @Transactional
    public void consumeTokenAndReset(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 8)
            throw new IllegalArgumentException("Password must be at least 8 characters");

        PasswordResetToken record = tokenRepo.findByTokenAndUsedFalse(rawToken)
                .orElseThrow(() -> new SecurityException("Invalid or expired reset token"));

        if (record.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new SecurityException("Reset token has expired. Please request a new one.");

        AdminUser admin = adminRepo.findById(record.getAdminId())
                .orElseThrow(() -> new SecurityException("Admin account not found"));

        if (!admin.isActive())
            throw new SecurityException("Account is inactive");

        // Mark token consumed before changing password (prevents race)
        record.setUsed(true);
        record.setUsedAt(OffsetDateTime.now());
        tokenRepo.save(record);

        admin.setPasswordHash(encoder.encode(newPassword));
        adminRepo.save(admin);

        auditLog.log("PASSWORD_RESET_COMPLETED", admin.getUsername(), "Via email reset token");
        log.info("[PWD-RESET] Password reset completed for {}", admin.getUsername());
    }

    // ── Purge expired tokens nightly ──────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = tokenRepo.deleteExpired(OffsetDateTime.now());
        if (deleted > 0) log.info("[PWD-RESET] Purged {} expired reset tokens", deleted);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private String generateSecureToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void sendResetEmail(String username, String toEmail, String resetLink) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");

        h.setFrom(fromAddress);
        h.setTo(toEmail);
        h.setSubject("[" + appName + "] Password Reset Request");

        String html = """
                <!DOCTYPE html>
                <html>
                <body style="font-family:Arial,sans-serif;background:#0f0f1a;color:#e2e8f0;padding:32px">
                  <div style="max-width:520px;margin:0 auto;background:#1a1a2e;border-radius:12px;
                              border:1px solid #2d2d4e;padding:32px">
                    <h2 style="color:#a78bfa;margin-top:0">Password Reset</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>A password reset was requested for your <strong>%s</strong> account.</p>
                    <p>Click the button below to set a new password.
                       This link expires in <strong>30 minutes</strong>.</p>
                    <div style="text-align:center;margin:28px 0">
                      <a href="%s"
                         style="background:#7c3aed;color:#fff;padding:12px 28px;
                                border-radius:8px;text-decoration:none;font-weight:bold;
                                display:inline-block">
                        Reset Password
                      </a>
                    </div>
                    <p style="font-size:12px;color:#94a3b8">
                      If you did not request this, ignore this email.
                      Your password will not change.
                    </p>
                    <hr style="border-color:#2d2d4e;margin:24px 0"/>
                    <p style="font-size:11px;color:#64748b;margin:0">
                      %s · Secure Voting Infrastructure
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(username, appName, resetLink, appName);

        h.setText(html, true);
        mailSender.send(msg);
    }
}
