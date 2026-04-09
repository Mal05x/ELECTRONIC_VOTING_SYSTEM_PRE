package com.evoting.service;

import com.evoting.model.*;
import com.evoting.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * MultiSigService — cryptographic multi-signature enforcement for state changes.
 *
 * Threshold:  2-of-N active SUPER_ADMINs (bootstrap: 1-of-1 if only one exists)
 * Algorithm:  ECDSA with SHA-256 over P-256 curve (Web Crypto default)
 * Payload:    Canonical string: "<actionType>|<targetId>|<changeId>"
 *             This binds the signature to the specific operation and target,
 *             preventing payload substitution attacks where a DB-level compromise
 *             could swap the targetId of an existing signed pending change.
 * Expiry:     24 hours — expired pending changes are auto-cancelled
 *
 * Actions requiring multi-sig:
 *   ACTIVATE_ELECTION, CLOSE_ELECTION, BULK_UNLOCK_CARDS,
 *   DEACTIVATE_ADMIN, ACTIVATE_ADMIN, PUBLISH_MERKLE_ROOT
 */
@Service @Slf4j
public class MultiSigService {

    @Autowired private PendingStateChangeRepository  changeRepo;
    @Autowired private StateChangeSignatureRepository sigRepo;
    @Autowired private AdminKeypairRepository         keypairRepo;
    @Autowired private AdminUserRepository            adminRepo;
    @Autowired private AuditLogService                auditLog;
    @Autowired private ApplicationContext             ctx;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Initiate a state change. Returns the pending change record.
     * If the initiator's signature is provided, it is recorded immediately.
     */
    @Transactional
    public PendingStateChange initiate(String actionType, String targetId,
                                       String targetLabel, Map<String, Object> payload,
                                       UUID initiatedBy, String initiatorSignature) {
        int required = computeThreshold();

        // Prevent duplicates — if an identical active pending change exists, return it
        List<PendingStateChange> existing = changeRepo.findActivePending(OffsetDateTime.now());
        for (PendingStateChange ex : existing) {
            if (ex.getActionType().equals(actionType) && ex.getTargetId().equals(targetId)) {
                log.warn("[MULTISIG] Duplicate state change prevented: {} on {}", actionType, targetId);
                return ex;
            }
        }

        PendingStateChange change = PendingStateChange.builder()
                .actionType(actionType)
                .targetId(targetId)
                .targetLabel(targetLabel)
                .payload(payload)
                .initiatedBy(initiatedBy)
                .signaturesRequired(required)
                .build();
        change = changeRepo.save(change);

        auditLog.log("STATE_CHANGE_INITIATED",
                adminRepo.findById(initiatedBy).map(AdminUser::getUsername).orElse("unknown"),
                "Action=" + actionType + " Target=" + targetId + " Required=" + required);

        // Record initiator signature if provided
        if (initiatorSignature != null && !initiatorSignature.isBlank()) {
            recordSignature(change.getId(), initiatedBy, initiatorSignature);
        }

        return change;
    }

    /**
     * Record a signature from a SUPER_ADMIN.
     * Verifies the ECDSA signature against the admin's registered public key.
     * Executes the action if threshold is met.
     *
     * @return true if action was executed (threshold met), false if still pending
     */
    @Transactional
    public boolean recordSignature(UUID changeId, UUID adminId, String base64Signature) {
        PendingStateChange change = changeRepo.findById(changeId)
                .orElseThrow(() -> new IllegalArgumentException("State change not found: " + changeId));

        if (change.isExecuted())  throw new IllegalStateException("Already executed");
        if (change.isCancelled()) throw new IllegalStateException("Change was cancelled");
        if (change.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalStateException("State change has expired");

        if (sigRepo.existsByChangeIdAndAdminId(changeId, adminId))
            throw new IllegalStateException("Admin already signed this change");

        // Verify ECDSA signature
        AdminKeypair keypair = keypairRepo.findByAdminId(adminId)
                .orElseThrow(() -> new IllegalStateException(
                        "No registered keypair for admin. Please register your key first."));

        // Use canonical payload — binds sig to action type and target, not just the change UUID
        String canonical = canonicalPayload(change.getActionType(), change.getTargetId(), changeId.toString());
        if (!verifySignature(canonical, base64Signature, keypair.getPublicKey())) {
            auditLog.log("STATE_CHANGE_INVALID_SIGNATURE",
                    adminRepo.findById(adminId).map(AdminUser::getUsername).orElse("unknown"),
                    "ChangeId=" + changeId);
            throw new IllegalStateException("Invalid ECDSA signature");
        }

        // Store valid signature
        StateChangeSignature sig = StateChangeSignature.builder()
                .changeId(changeId)
                .adminId(adminId)
                .signature(base64Signature)
                .build();
        sigRepo.save(sig);

        String username = adminRepo.findById(adminId).map(AdminUser::getUsername).orElse("unknown");
        long sigCount = sigRepo.countByChangeId(changeId);

        auditLog.log("STATE_CHANGE_SIGNED", username,
                "ChangeId=" + changeId + " Action=" + change.getActionType() +
                        " Signatures=" + sigCount + "/" + change.getSignaturesRequired());

        log.info("[MULTISIG] {} signed {} ({}/{})",
                username, change.getActionType(), sigCount, change.getSignaturesRequired());

        // Check threshold
        if (sigCount >= change.getSignaturesRequired()) {
            executeChange(change);
            return true;
        }
        return false;
    }

    /**
     * Cancel a pending state change. Only the initiator or a SUPER_ADMIN can cancel.
     */
    @Transactional
    public void cancel(UUID changeId, UUID cancelledBy, String reason) {
        PendingStateChange change = changeRepo.findById(changeId).orElseThrow();
        if (change.isExecuted())  throw new IllegalStateException("Already executed");
        if (change.isCancelled()) throw new IllegalStateException("Already cancelled");

        change.setCancelled(true);
        change.setCancelledBy(cancelledBy);
        change.setCancelledAt(OffsetDateTime.now());
        change.setCancelReason(reason);
        changeRepo.save(change);

        String username = adminRepo.findById(cancelledBy).map(AdminUser::getUsername).orElse("unknown");
        auditLog.log("STATE_CHANGE_CANCELLED", username,
                "ChangeId=" + changeId + " Action=" + change.getActionType() + " Reason=" + reason);
    }

    /**
     * Get all active (not executed, not cancelled, not expired) pending changes.
     */
    public List<PendingStateChange> getActivePending() {
        return changeRepo.findActivePending(OffsetDateTime.now());
    }

    /**
     * Get signature status for a pending change.
     */
    public Map<String, Object> getStatus(UUID changeId) {
        PendingStateChange change = changeRepo.findById(changeId).orElseThrow();
        List<StateChangeSignature> sigs = sigRepo.findByChangeId(changeId);

        List<Map<String, Object>> sigDetails = sigs.stream().map(s -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("adminId",  s.getAdminId().toString());
            d.put("username", adminRepo.findById(s.getAdminId())
                    .map(AdminUser::getUsername).orElse("unknown"));
            d.put("signedAt", s.getSignedAt().toString());
            return d;
        }).toList();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("changeId",   changeId.toString());
        status.put("actionType", change.getActionType());
        status.put("targetId",   change.getTargetId());
        status.put("targetLabel",change.getTargetLabel());
        status.put("required",   change.getSignaturesRequired());
        status.put("received",   sigs.size());
        status.put("remaining",  Math.max(0, change.getSignaturesRequired() - sigs.size()));
        status.put("executed",   change.isExecuted());
        status.put("cancelled",  change.isCancelled());
        status.put("expiresAt",     change.getExpiresAt().toString());
        status.put("signatures",    sigDetails);
        // Include the canonical signing payload so the frontend knows exactly what to sign.
        // Frontend: await signChallenge(status.signingPayload)
        status.put("signingPayload", canonicalPayload(
                change.getActionType(), change.getTargetId(), changeId.toString()));
        return status;
    }

    // ── Expire stale changes every 15 minutes ─────────────────────────────
    @Scheduled(fixedDelay = 900_000)
    @Transactional
    public void expireStale() {
        List<PendingStateChange> expired = changeRepo.findExpired(OffsetDateTime.now());
        for (PendingStateChange c : expired) {
            c.setCancelled(true);
            c.setCancelReason("Expired after 24 hours without reaching signature threshold");
            c.setCancelledAt(OffsetDateTime.now());
            changeRepo.save(c);
            log.info("[MULTISIG] Expired {} {}", c.getActionType(), c.getId());
        }
    }

    // ── Internal: execute the action once threshold is met ────────────────
    private void executeChange(PendingStateChange change) {
        change.setExecuted(true);
        change.setExecutedAt(OffsetDateTime.now());
        changeRepo.save(change);

        log.info("[MULTISIG] EXECUTING {} target={}", change.getActionType(), change.getTargetId());

        try {
            switch (change.getActionType()) {
                case "ACTIVATE_ELECTION"   -> ctx.getBean(ElectionExecutionService.class)
                        .activateElection(UUID.fromString(change.getTargetId()));
                case "CLOSE_ELECTION"      -> ctx.getBean(ElectionExecutionService.class)
                        .closeElection(UUID.fromString(change.getTargetId()));
                case "BULK_UNLOCK_CARDS"   -> ctx.getBean(ElectionExecutionService.class)
                        .bulkUnlockCards(UUID.fromString(change.getTargetId()));
                case "DEACTIVATE_ADMIN"    -> ctx.getBean(AdminExecutionService.class)
                        .deactivateAdmin(UUID.fromString(change.getTargetId()));
                case "ACTIVATE_ADMIN"      -> ctx.getBean(AdminExecutionService.class)
                        .activateAdmin(UUID.fromString(change.getTargetId()));
                case "PUBLISH_MERKLE_ROOT" -> ctx.getBean(ElectionExecutionService.class)
                        .publishMerkle(UUID.fromString(change.getTargetId()));
                case "REMOVE_CANDIDATE"    -> ctx.getBean(com.evoting.service.CandidateRemovalService.class)
                        .removeCandidate(UUID.fromString(change.getTargetId()), change.getTargetLabel());
                case "EXPORT_AUDIT_LOG"    -> log.info("[MULTISIG] EXPORT_AUDIT_LOG approved — " +
                        "audit export token released for change {}", change.getId());
                // Actual file delivery happens in AdminController which polls executed status
                default -> log.error("[MULTISIG] Unknown action type: {}", change.getActionType());
            }
            auditLog.log("STATE_CHANGE_EXECUTED", "SYSTEM",
                    "Action=" + change.getActionType() + " Target=" + change.getTargetId());
        } catch (Exception e) {
            log.error("[MULTISIG] Execution failed for {} {}: {}",
                    change.getActionType(), change.getId(), e.getMessage());
            // Mark as failed but do not un-execute — manual intervention required
            auditLog.log("STATE_CHANGE_EXECUTION_FAILED", "SYSTEM",
                    "ChangeId=" + change.getId() + " Error=" + e.getMessage());
        }
    }

    // ── Threshold calculation ─────────────────────────────────────────────
    private int computeThreshold() {
        long activeSuperAdmins = adminRepo.findAll().stream()
                .filter(a -> a.isActive() && "SUPER_ADMIN".equals(a.getRole() != null ? a.getRole().name() : ""))
                .count();
        // Bootstrap: if only 1 SUPER_ADMIN exists, threshold = 1
        return activeSuperAdmins <= 1 ? 1 : 2;
    }

    // ── Canonical signing payload ────────────────────────────────────────
    /**
     * Returns the canonical string that admins must sign for a given state change.
     *
     * Format: "<actionType>|<targetId>|<changeId>"
     *
     * This binds the signature cryptographically to the specific action and target.
     * Signing only the changeId UUID allowed a DB-level attacker to swap targetId
     * on a pending change after it was signed — the signature would still verify.
     * With this format, any tampering with actionType or targetId invalidates the sig.
     */
    static String canonicalPayload(String actionType, String targetId, String changeId) {
        return actionType + "|" + targetId + "|" + changeId;
    }

    // ── ECDSA P-256 signature verification ───────────────────────────────
    private boolean verifySignature(String payload, String base64Sig, String base64PubKey) {
        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(base64PubKey);
            byte[] sigBytes    = Base64.getDecoder().decode(base64Sig);

            KeyFactory kf  = KeyFactory.getInstance("EC");
            PublicKey  pub = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            //   Signature verifier = Signature.getInstance("SHA256withECDSA");
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(pub);
            verifier.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            log.error("[MULTISIG] Signature verification error: {}", e.getMessage());
            return false;
        }
    }
}
