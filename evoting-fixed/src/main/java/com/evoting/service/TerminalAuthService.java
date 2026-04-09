package com.evoting.service;

import com.evoting.model.TerminalRegistry;
import com.evoting.repository.TerminalRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * TerminalAuthService — application-layer terminal identity verification.
 *
 * Replaces mTLS for cloud deployments where the TLS proxy terminates
 * before the application (Render, Railway, Heroku, etc.).
 *
 * ── Signing Scheme ──────────────────────────────────────────────────────────
 *
 * The ESP32-S3 signs a canonical payload with its ECDSA P-256 private key:
 *
 *   canonical = terminalId + "|" + unixTimestamp + "|" + SHA256(requestBody)
 *
 * Headers sent with every request:
 *   X-Terminal-Id        : TERM-KD-001            (registered terminal ID)
 *   X-Request-Timestamp  : 1712345678             (Unix seconds, UTC)
 *   X-Terminal-Signature : <Base64 P1363 ECDSA>   (signature over canonical)
 *
 * Verification steps (all must pass):
 *   1. terminalId is registered and active in terminal_registry
 *   2. timestamp is within ±5 minutes of server time (replay protection)
 *   3. ECDSA signature verifies against the registered public key
 *
 * ── Key Storage on ESP32-S3 ─────────────────────────────────────────────────
 *
 * Basic (NVS): Private key stored in encrypted NVS partition.
 *   ESP-IDF NVS encryption uses an AES-XTS key derived from a hardware-unique
 *   256-bit key burned into eFuse. This key cannot be read back via software.
 *   To enable: idf.py menuconfig → Component config → NVS → Enable NVS encryption.
 *
 * Hardened (DS peripheral): ESP32-S3 has a dedicated Digital Signature peripheral.
 *   The private key is stored inside the DS block, protected by a key burned into
 *   eFuse. The CPU submits a hash; the DS block returns the signature. The raw
 *   private key is never accessible to the CPU. Use for election-day hardware.
 *   See: https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-reference/peripherals/ds.html
 *
 * For Arduino-framework builds (as in evoting_s3_merged_3.ino):
 *   Use Preferences library with NVS encryption enabled in sdkconfig.
 *   The private key JWK is stored under namespace "terminal_auth".
 */
@Service
@Slf4j
public class TerminalAuthService {

    /** Maximum age of a signed request — prevents replay attacks. */
    private static final long MAX_REQUEST_AGE_SECS = 300L; // 5 minutes

    @Autowired private TerminalRegistryRepository terminalRepo;
    @Autowired private AuditLogService auditLog;

    /**
     * Verify a signed terminal request.
     *
     * @param terminalId  From X-Terminal-Id header.
     * @param timestamp   From X-Request-Timestamp header (Unix seconds as string).
     * @param bodyHash    Base64 SHA-256 of the raw request body bytes.
     * @param signature   From X-Terminal-Signature header (Base64 P1363 ECDSA).
     * @throws SecurityException if any verification step fails.
     */
    public void verify(String terminalId, String timestamp,
                       String bodyHash, String signature) {

        // ── Step 1: Terminal registered and active ─────────────────────────
        TerminalRegistry terminal = terminalRepo
                .findByTerminalIdAndActiveTrue(terminalId)
                .orElseThrow(() -> {
                    auditLog.log("TERMINAL_AUTH_UNKNOWN", terminalId,
                            "Unregistered terminal attempted request");
                    return new SecurityException(
                            "Terminal not registered: " + terminalId);
                });

        // ── Step 2: Timestamp within ±5 minutes ───────────────────────────
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new SecurityException("Invalid X-Request-Timestamp format");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - requestTime) > MAX_REQUEST_AGE_SECS) {
            auditLog.log("TERMINAL_AUTH_REPLAY", terminalId,
                    "Timestamp delta=" + Math.abs(now - requestTime) + "s");
            throw new SecurityException(
                    "Request timestamp outside acceptable window (±5 min). "
                            + "Check terminal NTP sync.");
        }

        // ── Step 3: ECDSA signature ────────────────────────────────────────
        // Canonical string must exactly match what the firmware signs
        String canonical = terminalId + "|" + timestamp + "|" + bodyHash;

        if (!verifyECDSA(canonical, signature, terminal.getPublicKey())) {
            auditLog.log("TERMINAL_AUTH_BAD_SIG", terminalId,
                    "Signature verification failed");
            throw new SecurityException("Terminal signature verification failed");
        }

        // Update last-seen without triggering a full transaction overhead
        terminal.setLastSeen(java.time.OffsetDateTime.now());
        terminalRepo.save(terminal);

        log.debug("[TERMINAL-AUTH] Verified: {}", terminalId);
    }

    /**
     * Register a new terminal. Called by admin during provisioning.
     * Idempotent — if the terminal already exists, its public key is updated (key rotation).
     */
    public TerminalRegistry register(String terminalId, String publicKeyB64,
                                     String label, Integer pollingUnitId,
                                     String registeredBy) {
        TerminalRegistry existing = terminalRepo.findByTerminalIdAndActiveTrue(terminalId)
                .orElse(null);

        if (existing != null) {
            // Key rotation — update public key
            existing.setPublicKey(publicKeyB64);
            existing.setLabel(label);
            existing.setPollingUnitId(pollingUnitId);
            terminalRepo.save(existing);
            auditLog.log("TERMINAL_KEY_ROTATED", registeredBy,
                    "TerminalId=" + terminalId);
            return existing;
        }

        TerminalRegistry reg = TerminalRegistry.builder()
                .terminalId(terminalId)
                .publicKey(publicKeyB64)
                .label(label)
                .pollingUnitId(pollingUnitId)
                .registeredBy(registeredBy)
                .build();
        terminalRepo.save(reg);
        auditLog.log("TERMINAL_REGISTERED", registeredBy,
                "TerminalId=" + terminalId + " Label=" + label);
        return reg;
    }

    // ── ECDSA P-256 verification (SHA256withECDSAinP1363Format) ──────────
    private boolean verifyECDSA(String payload, String base64Sig, String base64PubKey) {
        try {
            byte[] pubBytes  = Base64.getDecoder().decode(base64PubKey);
            byte[] sigBytes  = Base64.getDecoder().decode(base64Sig);
            PublicKey pub    = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(pubBytes));
            // P1363Format matches mbedtls_pk_sign() raw output on ESP32
            Signature ver    = Signature.getInstance("SHA256withECDSAinP1363Format");
            ver.initVerify(pub);
            ver.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ver.verify(sigBytes);
        } catch (Exception e) {
            log.error("[TERMINAL-AUTH] ECDSA verification error: {}", e.getMessage());
            return false;
        }
    }
}
