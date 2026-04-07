package com.evoting.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast startup validation for required secrets.
 *
 * Required (will refuse to start if missing):
 *   AES_256_SECRET  — used for terminal packet encryption/decryption
 *   JWT_SECRET      — used for admin JWT signing and verification
 *
 * Optional (warnings logged, service degrades gracefully):
 *   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY — only required for S3
 *   candidate photo uploads. If unset, the image upload endpoints return
 *   503 but all other functionality works normally.
 */
@Configuration
@Slf4j
public class SecretsValidationConfig {

    @Value("${security.aes.secret-key}")
    private String aesKey;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    // Demographics encryption key — required; voter PII is encrypted with this
    @Value("${security.demographics-key}")
    private String demographicsKey;

    // Optional — S3 photo uploads only; null if env var not set
    @Value("${aws.access-key-id:#{null}}")
    private String awsAccessKey;

    @Value("${aws.secret-access-key:#{null}}")
    private String awsSecretKey;

    @PostConstruct
    public void validateSecrets() {
        StringBuilder failures = new StringBuilder();

        // ── Required secrets ──────────────────────────────────────────────
        if (isInsecure(aesKey)) {
            failures.append("\n  - AES_256_SECRET: not set or uses placeholder value. " +
                    "Generate with: openssl rand -base64 32");
        }
        if (isInsecure(jwtSecret)) {
            failures.append("\n  - JWT_SECRET: not set or uses placeholder value. " +
                    "Generate with: openssl rand -base64 64");
        }
        if (isInsecure(demographicsKey)) {
            failures.append("\n  - DEMOGRAPHICS_KEY: not set or uses placeholder value. " +
                    "Generate with: openssl rand -base64 32  " +
                    "(WARNING: changing this re-encrypts all voter demographics)");
        }

        if (failures.length() > 0) {
            throw new IllegalStateException(
                    "Refusing to start: the following required secrets are not configured:"
                            + failures
                            + "\n\nSet these environment variables before starting the application.");
        }

        // Validate AES key decodes to at least 32 bytes
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(aesKey);
            if (keyBytes.length < 32) {
                throw new IllegalStateException(
                        "AES_256_SECRET must decode to at least 32 bytes. " +
                                "Generate with: openssl rand -base64 32");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "AES_256_SECRET is not valid Base64. Generate with: openssl rand -base64 32");
        }

        log.info("Required secrets validated — AES and JWT keys are configured.");

        // ── Optional secrets (warn, don't fail) ───────────────────────────
        if (isInsecure(awsAccessKey) || isInsecure(awsSecretKey)) {
            log.warn("AWS credentials not set (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY). " +
                    "Candidate photo uploads (S3) will be unavailable. " +
                    "All other features work normally without S3.");
        } else {
            log.info("AWS credentials present — S3 image uploads enabled.");
        }
    }

    private boolean isInsecure(String value) {
        return value == null || value.isBlank()
                || value.contains("CHANGE_ME")
                || value.startsWith("${");
    }
}
