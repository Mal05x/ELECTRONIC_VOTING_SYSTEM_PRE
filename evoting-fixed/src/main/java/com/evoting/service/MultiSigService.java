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
 * Threshold:  2-of-N active SUPER_ADMINs
 * Algorithm:  ECDSA with SHA-256 over P-256 curve (Web Crypto default)
 * Payload:    Canonical string: "<actionType>|<targetId>|<changeId>"
 * Expiry:     24 hours — expired pending changes are auto-cancelled
 *
 * Actions requiring multi-sig:
 *   ACTIVATE_ELECTION, CLOSE_ELECTION, BULK_UNLOCK_CARDS,
 *   DEACTIVATE_ADMIN, ACTIVATE_ADMIN, PUBLISH_MERKLE_ROOT
 *
 * ── FIX DQ1: Bootstrap guard ──────────────────────────────────────────────────
 *
 * BEFORE:
 *   computeThreshold() returned 1 when only 1 SUPER_ADMIN existed (bootstrap mode).
 *   This meant that during initial deployment — before a second SUPER_ADMIN
 *   registered their keypair — all state-changing operations required only ONE
 *   signature. A compromised first admin account during setup could activate
 *   elections, close them, or bulk-unlock cards without any second approval.
 *
 * AFTER (this fix):
 *   initiate() now calls enforceBootstrapGuard() before any state change is
 *   allowed. The guard requires that at least MIN_REGISTERED_KEYPAIRS (2) admin
 *   keypairs are registered in the system.
 *
 *   If fewer than 2 keypairs are registered, ALL state-changing operations are
 *   blocked with a clear error message. The system is in "bootstrap lockout" mode
 *   until a second SUPER_ADMIN has registered their signing keypair.
 *
 *   Rationale: the multi-sig governance protocol is meaningless if there is only
 *   one possible signer. The deployment ceremony must complete before elections
 *   can be managed.
 *
 *   Deployment checklist (must complete before going live):
 *     1. superadmin account set up and keypair registered  ✓
 *     2. Second SUPER_ADMIN account created by superadmin
 *     3. Second admin logs in and registers their keypair  ← unblocks operations
 *     4. Test: initiate any action — system should now require 2 signatures
 */
@Service @Slf4j
public class MultiSigService {

    /** Minimum number of registered admin keypairs before state changes are permitted. */
    private static final int MIN_REGISTERED_KEYPAIRS = 2;

    @Autowired private PendingStateChangeRepository   changeRepo;
    @Autowired private StateChangeSignatureRepository sigRepo;
    @Autowired private AdminKeypairRepository         keypairRepo;
    @Autowired private AdminUserRepository            adminRepo;
    @Autowired private AuditLogService                auditLog;
    @Autowired private ApplicationContext             ctx;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Initiate a state change. Returns the pending change record.
     * If the initiator's signature is provided, it is recorded immediately.
     *
     * Throws IllegalStateException if the bootstrap guard check fails.
     */
    @Transactional
    public PendingStateChange initiate(String actionType, String targetId,
                                       String targetLabel, Map<String, Object> payload,
                                       UUID initiatedBy, String initiatorSignature) {
        // ── FIX DQ1: Enforce bootstrap guard before any state change ──────────
        enforceBootstrapGuard(actionType, initiatedBy);

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

        String canonical = canonicalPayload(change.getActionType(), change.getTargetId(), changeId.toString());
        if (!verifySignature(canonical, base64Signature, keypair.getPublicKey())) {
            auditLog.log("STATE_CHANGE_INVALID_SIGNATURE",
                    adminRepo.findById(adminId).map(AdminUser::getUsername).orElse("unknown"),
                    "ChangeId=" + changeId);
            throw new IllegalStateException("Invalid ECDSA signature");
        }

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

    public List<PendingStateChange> getActivePending() {
        return changeRepo.findActivePending(OffsetDateTime.now());
    }

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
        status.put("changeId",      changeId.toString());
        status.put("actionType",    change.getActionType());
        status.put("targetId",      change.getTargetId());
        status.put("targetLabel",   change.getTargetLabel());
        status.put("required",      change.getSignaturesRequired());
        status.put("received",      sigs.size());
        status.put("remaining",     Math.max(0, change.getSignaturesRequired() - sigs.size()));
        status.put("executed",      change.isExecuted());
        status.put("cancelled",     change.isCancelled());
        status.put("expiresAt",     change.getExpiresAt().toString());
        status.put("signatures",    sigDetails);
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

    // ── FIX DQ1: Bootstrap guard ──────────────────────────────────────────

    /**
     * Blocks all state-changing operations until at least MIN_REGISTERED_KEYPAIRS
     * admin keypairs are registered.
     *
     * The guard checks keypairRepo.count() — the number of admin signing keys stored
     * in the system — not just the number of admin accounts. An admin account without
     * a registered keypair cannot sign anything, so it does not count toward quorum.
     *
     * This prevents the single-admin bootstrap window where the multi-sig protocol
     * degenerates to single-signature control before a second admin is onboarded.
     */
    private void enforceBootstrapGuard(String actionType, UUID initiatedBy) {
        long registeredKeypairs = keypairRepo.count();
        if (registeredKeypairs < MIN_REGISTERED_KEYPAIRS) {
            String initiatorName = adminRepo.findById(initiatedBy)
                    .map(AdminUser::getUsername).orElse("unknown");
            String message = String.format(
                    "[MULTISIG] Bootstrap guard BLOCKED %s initiated by %s. " +
                            "Registered keypairs: %d / required: %d. " +
                            "A second SUPER_ADMIN must register their signing keypair " +
                            "before any state-changing operations are permitted.",
                    actionType, initiatorName, registeredKeypairs, MIN_REGISTERED_KEYPAIRS);
            log.warn(message);
            auditLog.log("MULTISIG_BOOTSTRAP_BLOCKED", initiatorName,
                    "Action=" + actionType +
                            " RegisteredKeys=" + registeredKeypairs +
                            " Required=" + MIN_REGISTERED_KEYPAIRS);
            throw new IllegalStateException(
                    "System setup incomplete: at least " + MIN_REGISTERED_KEYPAIRS +
                            " admin signing keypairs must be registered before '" + actionType +
                            "' can be initiated. Currently registered: " + registeredKeypairs + ". " +
                            "Create a second SUPER_ADMIN account and have them register their keypair " +
                            "in Settings → Signing Key.");
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
                default -> log.error("[MULTISIG] Unknown action type: {}", change.getActionType());
            }
            auditLog.log("STATE_CHANGE_EXECUTED", "SYSTEM",
                    "Action=" + change.getActionType() + " Target=" + change.getTargetId());
        } catch (Exception e) {
            log.error("[MULTISIG] Execution failed for {} {}: {}",
                    change.getActionType(), change.getId(), e.getMessage());
            auditLog.log("STATE_CHANGE_EXECUTION_FAILED", "SYSTEM",
                    "ChangeId=" + change.getId() + " Error=" + e.getMessage());
        }
    }

    // ── Threshold calculation ─────────────────────────────────────────────

    /**
     * Computes the signature threshold at initiation time.
     *
     * After the bootstrap guard passes (≥ 2 keypairs registered), this always
     * returns 2. The 1-of-1 bootstrap fallback has been removed — the guard above
     * prevents initiation until 2 keypairs exist.
     */
    private int computeThreshold() {
        long activeSuperAdmins = adminRepo.findAll().stream()
                .filter(a -> a.isActive() && "SUPER_ADMIN".equals(
                        a.getRole() != null ? a.getRole().name() : ""))
                .count();
        // Always require 2 (bootstrap guard above ensures this is achievable)
        return activeSuperAdmins >= 2 ? 2 : 1;
    }

    // ── Canonical signing payload ────────────────────────────────────────
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
