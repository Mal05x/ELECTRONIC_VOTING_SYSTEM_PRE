package com.evoting.controller;

import com.evoting.dto.*;
import com.evoting.model.PendingRegistration;
import com.evoting.repository.PendingRegistrationRepository;
import com.evoting.service.DemographicsService;
import com.evoting.service.RegistrationService;
import com.evoting.security.RequiresStepUp;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RegistrationController
 *
 * Terminal endpoint (no auth — terminal uses mTLS):
 *   POST /api/terminal/pending-registration
 *     Terminal scans card → creates pending record
 *
 * Admin endpoints (JWT required):
 *   GET  /api/admin/voters/pending         — list cards awaiting demographics
 *   POST /api/admin/voters/commit-registration/{pendingId}  — commit with demographics
 *   DELETE /api/admin/voters/pending/{id}  — cancel pending registration
 *   GET  /api/admin/voters/{voterId}/demographics — decrypt + return demographics (audit logged)
 */
@RestController
@Slf4j
public class RegistrationController {

    @Autowired private RegistrationService           regService;
    @Autowired private PendingRegistrationRepository pendingRepo;
    @Autowired private DemographicsService           demoService;

    // ── Terminal: initiate registration ───────────────────────────────────
    @PostMapping("/api/terminal/pending-registration")
    public ResponseEntity<?> terminalInitiate(
            @RequestBody @Valid TerminalPendingRegistrationDTO dto) {
        PendingRegistration pending = regService.initiateFromTerminal(
                dto.getTerminalId(), dto.getPollingUnitId(),
                dto.getCardIdHash(), dto.getVoterPublicKey());

        return ResponseEntity.ok(Map.of(
                "pendingId",  pending.getId().toString(),
                "status",     pending.getStatus(),
                "expiresAt",  pending.getExpiresAt().toString(),
                "message",    "Card registered. Ask admin to complete demographics entry."
        ));
    }

    // ── Admin: list pending cards awaiting demographics ───────────────────
    @GetMapping("/api/admin/voters/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> listPending() {
        List<Map<String, Object>> records = pendingRepo
                .findByStatus("AWAITING_DEMOGRAPHICS").stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pendingId",    p.getId().toString());
                    m.put("terminalId",   p.getTerminalId());
                    m.put("cardIdHash",   p.getCardIdHash());
                    m.put("pollingUnitId",p.getPollingUnitId());
                    m.put("initiatedAt",  p.getInitiatedAt().toString());
                    m.put("expiresAt",    p.getExpiresAt().toString());
                    return m;
                }).toList();

        return ResponseEntity.ok(Map.of("pending", records, "count", records.size()));
    }

    // ── Admin: commit registration with demographics ───────────────────────
    @PostMapping("/api/admin/voters/commit-registration/{pendingId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @RequiresStepUp("COMMIT_REGISTRATION")
    public ResponseEntity<?> commit(
            @PathVariable UUID pendingId,
            @RequestBody @Valid CommitRegistrationDTO dto,
            Authentication auth) {
        Map<String, Object> result = regService.commitRegistration(
                pendingId, dto, auth.getName());
        return ResponseEntity.ok(result);
    }

    // ── Admin: cancel a pending registration ─────────────────────────────
    @DeleteMapping("/api/admin/voters/pending/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> cancelPending(
            @PathVariable UUID id, Authentication auth) {
        regService.cancelPending(id, auth.getName());
        return ResponseEntity.ok(Map.of("message", "Pending registration cancelled"));
    }

    // ── Admin: decrypt and view demographics (audit logged) ───────────────
    @GetMapping("/api/admin/voters/{voterId}/demographics")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getDemographics(
            @PathVariable UUID voterId, Authentication auth) {
        String json = demoService.decryptAndLog(voterId, auth.getName());
        if (json == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "voterId", voterId.toString(),
                "data",    json,
                "warning", "This access has been permanently recorded in the audit log."
        ));
    }
}
