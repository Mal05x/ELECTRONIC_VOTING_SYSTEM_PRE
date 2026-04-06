package com.evoting.service;

import com.evoting.model.ActionChallenge;
import com.evoting.model.AdminKeypair;
import com.evoting.repository.ActionChallengeRepository;
import com.evoting.repository.AdminKeypairRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * StepUpAuthService — ECDSA step-up authentication for sensitive actions.
 *
 * Flow:
 *   1. Admin requests a challenge nonce for a specific action type
 *   2. Frontend signs the nonce with the admin's ECDSA private key
 *   3. Backend verifies signature, marks nonce as used, allows action
 *
 * Protected actions:
 *   QUEUE_ENROLLMENT, COMMIT_REGISTRATION, IMPORT_CANDIDATES,
 *   DELETE_CANDIDATE, CREATE_ELECTION, CREATE_PARTY
 *
 * Security properties:
 *   - Nonces expire after 60 seconds
 *   - Nonces are single-use (replay prevention)
 *   - Signature is over "ACTION_TYPE:NONCE" to bind sig to the action
 *   - Expired nonces purged every 5 minutes
 */
@Service @Slf4j
public class StepUpAuthService {

    public static final Set<String> PROTECTED_ACTIONS = Set.of(
            "QUEUE_ENROLLMENT",
            "COMMIT_REGISTRATION",
            "IMPORT_CANDIDATES",
            "DELETE_CANDIDATE",
            "CREATE_ELECTION",
            "CREATE_PARTY"
    );

    @Autowired private ActionChallengeRepository challengeRepo;
    @Autowired private AdminKeypairRepository     keypairRepo;
    @Autowired private AuditLogService            auditLog;

    // ── Generate a challenge nonce ────────────────────────────────────────

    @Transactional
    public Map<String, Object> generateChallenge(UUID adminId, String actionType) {
        if (!PROTECTED_ACTIONS.contains(actionType)) {
            throw new IllegalArgumentException("Unknown action type: " + actionType);
        }

        // Ensure admin has a registered keypair
        if (!keypairRepo.existsByAdminId(adminId)) {
            throw new IllegalStateException(
                    "No signing key registered. Go to Settings → Security & 2FA to set up your key.");
        }

        // Generate a cryptographically random nonce
        String nonce = UUID.randomUUID().toString().replace("-", "") +
                Long.toHexString(System.currentTimeMillis());

        ActionChallenge challenge = ActionChallenge.builder()
                .adminId(adminId)
                .nonce(nonce)
                .actionType(actionType)
                .build();

        challengeRepo.save(challenge);

        log.debug("[STEP-UP] Challenge issued: action={} admin={}", actionType, adminId);

        return Map.of(
                "nonce",      nonce,
                "actionType", actionType,
                "expiresIn",  60,
                "sigPayload", actionType + ":" + nonce  // exactly what the frontend must sign
        );
    }

    // ── Verify a signed challenge ─────────────────────────────────────────

    @Transactional
    public void verify(UUID adminId, String nonce, String base64Signature, String actionType) {
        // Look up the challenge
        ActionChallenge challenge = challengeRepo.findByNonce(nonce)
                .orElseThrow(() -> new SecurityException("Invalid or expired challenge nonce"));

        // Must belong to this admin
        if (!challenge.getAdminId().equals(adminId)) {
            log.warn("[STEP-UP] Nonce admin mismatch: expected={} got={}", challenge.getAdminId(), adminId);
            throw new SecurityException("Challenge nonce does not belong to this admin");
        }

        // Must not be expired
        if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new SecurityException("Challenge nonce has expired. Please try again.");
        }

        // Must not be already used
        if (challenge.isUsed()) {
            throw new SecurityException("Challenge nonce already used. Please request a new one.");
        }

        // Action type must match
        if (!challenge.getActionType().equals(actionType)) {
            log.warn("[STEP-UP] Action mismatch: expected={} got={}", challenge.getActionType(), actionType);
            throw new SecurityException("Action type mismatch");
        }

        // Verify ECDSA signature
        AdminKeypair keypair = keypairRepo.findByAdminId(adminId)
                .orElseThrow(() -> new SecurityException("No signing key registered for this admin"));

        String sigPayload = actionType + ":" + nonce;
        if (!verifySignature(sigPayload, base64Signature, keypair.getPublicKey())) {
            auditLog.log("STEP_UP_INVALID_SIGNATURE", adminId.toString(),
                    "Action=" + actionType + " Nonce=" + nonce);
            throw new SecurityException("Invalid ECDSA signature — action not authorized");
        }

        // Mark nonce as used
        challenge.setUsed(true);
        challenge.setUsedAt(OffsetDateTime.now());
        challengeRepo.save(challenge);

        auditLog.log("STEP_UP_AUTHORIZED", adminId.toString(),
                "Action=" + actionType);
        log.info("[STEP-UP] Authorized: action={} admin={}", actionType, adminId);
    }

    // ── ECDSA P-256 verification ──────────────────────────────────────────

    private boolean verifySignature(String payload, String base64Sig, String base64PubKey) {
        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(base64PubKey);
            byte[] sigBytes    = Base64.getDecoder().decode(base64Sig);
            KeyFactory kf  = KeyFactory.getInstance("EC");
            PublicKey  pub = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));
            // THE FIX: Append 'inP1363Format' to parse raw WebCrypto signatures!
            Signature  ver = Signature.getInstance("SHA256withECDSAinP1363Format");
            ver.initVerify(pub);
            ver.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ver.verify(sigBytes);
        } catch (Exception e) {
            log.error("[STEP-UP] Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    // ── Purge expired nonces every 5 minutes ─────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void purgeExpired() {
        int deleted = challengeRepo.deleteExpired(OffsetDateTime.now());
        if (deleted > 0) log.debug("[STEP-UP] Purged {} expired challenges", deleted);
    }
}
