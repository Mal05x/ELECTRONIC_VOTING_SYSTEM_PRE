package com.evoting.controller;

import com.evoting.dto.*;
import com.evoting.model.*;
import com.evoting.repository.*;
import com.evoting.service.MultiSigService;
import com.evoting.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.*;

/**
 * MultiSigController — endpoints for multi-signature state change management.
 *
 * All endpoints require SUPER_ADMIN role.
 *
 * Workflow:
 *   1. POST /api/admin/keypair          — register your ECDSA public key (first-time setup)
 *   2. GET  /api/admin/keypair/status   — check if you have a registered keypair
 *   3. POST /api/admin/state-changes/{action}/{targetId}  — initiate a state change
 *   4. GET  /api/admin/state-changes    — see all pending changes (for co-signing)
 *   5. POST /api/admin/state-changes/{id}/sign  — submit your ECDSA signature
 *   6. POST /api/admin/state-changes/{id}/cancel — cancel a pending change
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
public class MultiSigController {

    @Autowired private MultiSigService       multiSig;
    @Autowired private AdminKeypairRepository keypairRepo;
    @Autowired private AdminUserRepository    adminRepo;
    @Autowired private AuditLogService        auditLog;

    // ── Keypair management ────────────────────────────────────────────────

    @PostMapping("/keypair")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> registerKeypair(
            @RequestBody @Valid RegisterKeypairDTO dto,
            Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();

        // Upsert — update public key if keypair already exists (e.g. key rotation)
        AdminKeypair kp = keypairRepo.findByAdminId(admin.getId())
                .map(existing -> {
                    existing.setPublicKey(dto.getPublicKey());
                    return existing;
                })
                .orElseGet(() -> AdminKeypair.builder()
                        .adminId(admin.getId())
                        .publicKey(dto.getPublicKey())
                        .build());
        keypairRepo.save(kp);

        auditLog.log("KEYPAIR_REGISTERED", auth.getName(), "AdminId=" + admin.getId());
        return ResponseEntity.ok(Map.of(
                "message",  "Keypair registered successfully",
                "adminId",  admin.getId().toString(),
                "username", admin.getUsername()
        ));
    }

    @GetMapping("/keypair/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getKeypairStatus(Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();
        boolean hasKey  = keypairRepo.existsByAdminId(admin.getId());

        // Count how many SUPER_ADMINs have registered keys
        long totalSuperAdmins = adminRepo.findAll().stream()
                .filter(a -> a.isActive() && "SUPER_ADMIN".equals(a.getRole() != null ? a.getRole().name() : ""))
                .count();
        long registeredKeys = keypairRepo.count();

        return ResponseEntity.ok(Map.of(
                "hasKeypair",          hasKey,
                "threshold",           totalSuperAdmins <= 1 ? 1 : 2,
                "totalSuperAdmins",    totalSuperAdmins,
                "adminsWithKeypairs",  registeredKeys,
                "bootstrapMode",       totalSuperAdmins <= 1
        ));
    }

    // ── Initiate state changes ────────────────────────────────────────────

    /**
     * Step 1 — Initiate a state change.
     * No signature needed at this point — creates the pending record and returns the changeId.
     * The initiator then signs the changeId client-side and calls /sign.
     */
    @PostMapping("/state-changes/{action}/{targetId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> initiateStateChange(
            @PathVariable String action,
            @PathVariable String targetId,
            Authentication auth) {

        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();

        Set<String> validActions = Set.of(
                "ACTIVATE_ELECTION", "CLOSE_ELECTION", "BULK_UNLOCK_CARDS",
                "DEACTIVATE_ADMIN", "ACTIVATE_ADMIN", "PUBLISH_MERKLE_ROOT"
        );
        if (!validActions.contains(action.toUpperCase()))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid action type: " + action));

        PendingStateChange change = multiSig.initiate(
                action.toUpperCase(), targetId,
                buildLabel(action, targetId),
                null, admin.getId(),
                null  // no signature at initiation — initiator signs in step 2
        );

        Map<String, Object> status = multiSig.getStatus(change.getId());
        return ResponseEntity.ok(Map.of(
                "changeId",  change.getId().toString(),
                "pending",   !change.isExecuted(),
                "message",   change.isExecuted()
                        ? "Action executed (bootstrap — only 1 SUPER_ADMIN exists)"
                        : "Change initiated. Sign it now to register your approval.",
                "status",    status
        ));
    }

    // ── List pending changes ──────────────────────────────────────────────

    @GetMapping("/state-changes")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> listPending(Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();

        List<Map<String, Object>> changes = multiSig.getActivePending().stream()
                .map(c -> {
                    Map<String, Object> s = multiSig.getStatus(c.getId());
                    // Flag whether this admin has already signed
                    List<?> sigs = (List<?>) s.get("signatures");
                    boolean mySig = sigs.stream().anyMatch(sig ->
                            auth.getName().equals(((Map<?,?>)sig).get("username")));
                    s.put("iSigned",    mySig);
                    s.put("canSign",    !mySig && !c.isExecuted() && !c.isCancelled());
                    s.put("initiatedByMe", c.getInitiatedBy().equals(admin.getId()));
                    return s;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "pending", changes,
                "count",   changes.size()
        ));
    }

    // ── Sign a pending change ─────────────────────────────────────────────

    @PostMapping("/state-changes/{id}/sign")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> sign(
            @PathVariable UUID id,
            @RequestBody SignStateChangeDTO dto,
            Authentication auth) {
        if (dto.getSignature() == null || dto.getSignature().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Signature is required for co-signing"));
        }
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();

        boolean executed = multiSig.recordSignature(id, admin.getId(), dto.getSignature());
        Map<String, Object> status = multiSig.getStatus(id);

        return ResponseEntity.ok(Map.of(
                "executed", executed,
                "message",  executed ? "Threshold reached — action executed" : "Signature recorded",
                "status",   status
        ));
    }

    // ── Cancel a pending change ───────────────────────────────────────────

    @PostMapping("/state-changes/{id}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();
        String reason   = body != null ? body.getOrDefault("reason", "Cancelled by admin") : "Cancelled by admin";

        multiSig.cancel(id, admin.getId(), reason);
        return ResponseEntity.ok(Map.of("message", "State change cancelled", "changeId", id.toString()));
    }

    // ── Status of a specific change ───────────────────────────────────────

    @GetMapping("/state-changes/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> status(@PathVariable UUID id) {
        return ResponseEntity.ok(multiSig.getStatus(id));
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private String buildLabel(String action, String targetId) {
        return switch (action.toUpperCase()) {
            case "ACTIVATE_ELECTION"   -> "Activate election " + targetId;
            case "CLOSE_ELECTION"      -> "Close election " + targetId;
            case "BULK_UNLOCK_CARDS"   -> "Bulk unlock cards for election " + targetId;
            case "DEACTIVATE_ADMIN"    -> "Deactivate admin account " + targetId;
            case "ACTIVATE_ADMIN"      -> "Reactivate admin account " + targetId;
            case "PUBLISH_MERKLE_ROOT" -> "Publish Merkle root for election " + targetId;
            default                    -> action + " on " + targetId;
        };
    }
}
