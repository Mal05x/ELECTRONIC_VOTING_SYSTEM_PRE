package com.evoting.controller;

import com.evoting.dto.*;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.Candidate;
import com.evoting.model.Election;
import com.evoting.repository.CandidateRepository;
import com.evoting.repository.ElectionRepository;
import com.evoting.service.EnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.evoting.model.TerminalHeartbeat;
import com.evoting.repository.TerminalHeartbeatRepository;
import com.evoting.service.VoteProcessingService;
import com.evoting.dto.VoteReceiptDTO;
import com.evoting.exception.InvalidSessionException;
import com.evoting.model.TerminalRegistry;
import com.evoting.model.VoterRegistry;
import com.evoting.model.VotingSession;
import com.evoting.repository.TerminalRegistryRepository;
import com.evoting.repository.VoterRegistryRepository;
import com.evoting.repository.VotingSessionRepository;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/terminal")
@Slf4j
public class TerminalController {

    @Autowired private EnrollmentService enrollmentService;
    @Autowired private CandidateRepository candidateRepo;
    @Autowired private ElectionRepository electionRepo;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private TerminalHeartbeatRepository terminalHeartbeatRepo;
    @Autowired private VotingSessionRepository sessionRepo;
    @Autowired private VoterRegistryRepository voterRegistryRepo;

    /**
     * PATCH-3: Wired to VoteProcessingService — replaces the stub that
     * logged and discarded the vote. VoteProcessingService handles:
     *   - Idempotency (duplicate detection)
     *   - Session token validation
     *   - markAsVotedAndLock (atomic DB update)
     *   - ECDSA card burn-proof verification
     *   - Ballot persistence to ballot_box
     *   - Tally increment
     *   - Merkle root update
     *   - Anomaly detection recording
     */
    @Autowired private VoteProcessingService voteService;

    // Injects the Base64 AES key from application.yml / environment variables
    @Value("${security.aes.secret-key}")
    private String aesKeyBase64;

    // ── ESP32 AUTHENTICATION & HEARTBEAT ──────────────────────────────────────

    /**
     * POST /api/terminal/authenticate — NOT IMPLEMENTED.
     *
     * This endpoint previously decrypted a payload and returned a random UUID
     * sessionToken that was never persisted or bound to any session, implying
     * an authentication step that did nothing. The firmware (network.cpp) does
     * NOT call this endpoint — terminal identity is established via the
     * ECDSA-signed headers verified by TerminalAuthFilter on every request,
     * and voting sessions are created by POST /api/terminal/tap.
     *
     * Returning 501 so any stale firmware or tooling that calls this endpoint
     * gets a clear, honest error instead of a silently-minted unbound token.
     *
     * TODO: Remove this endpoint entirely once confirmed no external client
     * depends on it. If a real authentication handshake is needed in future,
     * wire it to EphemeralKeyService for proper ECDH session key derivation.
     */
    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticateTerminal(HttpServletRequest request,
                                                   @RequestBody Map<String, String> body) {
        log.warn("[AUTHENTICATE] POST /api/terminal/authenticate called by terminal={} — " +
                 "this endpoint is not implemented. Terminal auth is handled by " +
                 "TerminalAuthFilter (ECDSA headers) and /tap (session creation).",
                 request.getHeader("X-Terminal-Id"));
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "error",  "This endpoint is not implemented.",
                        "detail", "Terminal authentication is performed via ECDSA-signed " +
                                  "request headers (TerminalAuthFilter). Use POST /api/terminal/tap " +
                                  "to create a voting session."
                ));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> receiveHeartbeat(@RequestBody TerminalHeartbeat payload) {
        // THE FIX: Grab the terminal ID directly from the JSON payload
        // because the cloud load balancer swallows the mTLS certificate!
        String cleanTerminalId = payload.getTerminalId();

        if (cleanTerminalId == null || cleanTerminalId.isEmpty()) {
            log.warn("Heartbeat rejected: Missing terminalId in payload");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // UPSERT LOGIC: Find existing terminal, or use the new one if it doesn't exist
        TerminalHeartbeat heartbeat = terminalHeartbeatRepo.findByTerminalId(cleanTerminalId)
                .orElse(new TerminalHeartbeat());

        // Update the fields with the fresh data from the ESP32
        heartbeat.setTerminalId(cleanTerminalId);
        heartbeat.setBatteryLevel(payload.getBatteryLevel());
        heartbeat.setTamperFlag(payload.isTamperFlag());
        heartbeat.setIpAddress(payload.getIpAddress());
        heartbeat.setReportedAt(java.time.OffsetDateTime.now());

        // Save it back to PostgreSQL
        terminalHeartbeatRepo.save(heartbeat);

        log.info("💓 Heartbeat processed for: {}", cleanTerminalId);

        return ResponseEntity.ok().build();
    }



    /**
     * POST /api/terminal/vote
     *
     * PATCH-3: Stub replaced — now routes through VoteProcessingService for
     * full ballot persistence, idempotency, card locking, and tally update.
     *
     * Expected body: { "payload": "<AES-256-GCM encrypted VotePacketDTO>" }
     *
     * The AES key is read from application.yml (security.aes.secret-key),
     * NOT the hardcoded BACKEND_AES_KEY — that key has been removed from the
     * final vote path. See note below.
     *
     * ⚠️  SECURITY NOTE — HARDCODED KEY:
     * BACKEND_AES_KEY (0x01..0x20) is still present in this class for the
     * /authenticate endpoint decryption. That key MUST be rotated before
     * production deployment and moved to application.yml / env var.
     * Track as a separate task.
     */
    @PostMapping("/vote")
    public ResponseEntity<?> submitVote(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {

        // Terminal identity verified upstream by TerminalAuthFilter (ECDSA app-layer signing).
        // X-Terminal-Id header is trusted here — filter already validated the signature.
        String terminalSubject = request.getHeader("X-Terminal-Id");
        if (terminalSubject == null || terminalSubject.isBlank()) {
            log.warn("[VOTE] Rejected: missing X-Terminal-Id header");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Terminal not identified"));
        }

        String encryptedPayload = body.get("payload");
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing 'payload' field"));
        }

        try {
            // 2. Delegate to VoteProcessingService — handles all persistence logic
            VoteReceiptDTO receipt = voteService.processVote(encryptedPayload);
            log.info("[VOTE] Accepted from terminal {} — TxID={}", terminalSubject, receipt.getTransactionId());
            return ResponseEntity.ok(Map.of(
                    "transactionId",      receipt.getTransactionId(),
                    "encryptedAck",       receipt.getEncryptedAck(),
                    "message",            receipt.getMessage()
            ));

        } catch (InvalidSessionException e) {
            // Session expired, already used, or voter already voted — do not leak detail
            log.warn("[VOTE] Rejected from terminal {}: {}", terminalSubject, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Vote submission failed"));

        } catch (Exception e) {
            // Unexpected error — log full detail server-side only
            log.error("[VOTE] Processing error from terminal {}", terminalSubject, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Vote processing failed"));
        }
    }

    /**
     * GET /api/terminal/officer-pin-hash
     *
     * Returns the SHA-256 hex hash of the officer PIN provisioned for this
     * terminal. Called by the terminal firmware on first boot (or whenever
     * the hash is absent from NVS) over the ECDSA-authenticated channel.
     *
     * This endpoint is NOT in EXEMPT_PATHS — the terminal must provide a
     * valid X-Terminal-Signature header (verified by TerminalAuthFilter).
     * This guarantees only the genuine registered terminal can fetch its
     * own hash — another terminal cannot retrieve a different terminal's hash.
     */
    @GetMapping("/officer-pin-hash")
    public ResponseEntity<?> getOfficerPinHash(HttpServletRequest request) {
        String terminalId = request.getHeader("X-Terminal-Id");
        if (terminalId == null || terminalId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "X-Terminal-Id header required"));
        }

        TerminalRegistry reg = terminalRegistryRepo
                .findByTerminalIdAndActiveTrue(terminalId)
                .orElse(null);
        if (reg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "Terminal not registered"));
        }
        if (reg.getOfficerPinHash() == null) {
            // Admin has not yet set an officer PIN for this terminal
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error",  "Officer PIN not provisioned for this terminal",
                            "action", "Contact SUPER_ADMIN to set officer PIN via " +
                                    "PUT /api/admin/terminals/{terminalId}/officer-pin"));
        }

        return ResponseEntity.ok(Map.of("pinHash", reg.getOfficerPinHash()));
    }

    // ── ORIGINAL ENROLLMENT & CANDIDATE LOGIC ─────────────────────────────────

    @GetMapping("/candidates/{electionId}")
    public ResponseEntity<?> getCandidates(@PathVariable UUID electionId) {
        Election election = electionRepo.findById(electionId).orElse(null);
        if (election == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Election not found"));
        if (election.getStatus() != Election.ElectionStatus.ACTIVE) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Election is not active"));

        List<TerminalCandidateDTO> candidates = candidateRepo.findByElectionId(electionId).stream()
                .map(c -> new TerminalCandidateDTO(c.getId(), c.getFullName(), c.getParty(), c.getPosition(), c.getImageUrl()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(candidates);
    }

    @GetMapping("/pending_enrollment")
    public ResponseEntity<?> getPendingEnrollment(@RequestParam String terminalId) {
        TerminalEnrollmentRecordDTO record = enrollmentService.getPendingEnrollment(terminalId);
        if (record == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(record);
    }

    @PostMapping("/enrollment")
    public ResponseEntity<?> completeEnrollment(@RequestBody @Valid EnrollmentResultDTO dto) {
        try {
            VoterRegistrationResponseDTO result = enrollmentService.completeEnrollment(dto, dto.getTerminalId());
            return ResponseEntity.ok(result);
        } catch (EvotingAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Enrollment completion error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Enrollment processing failed"));
        }
    }

@PostMapping("/tap")
    public ResponseEntity<?> handleTerminalTap(@RequestBody java.util.Map<String, String> payload) {
        String terminalId = payload.get("terminalId");
        String electionId = payload.get("electionId");
        String cardIdHash = payload.get("cardIdHash");  // BUG-4: now required for voter geo lookup

        log.info("[TAP] terminalId={} electionId={} cardIdHash={}",
                terminalId, electionId,
                cardIdHash != null ? cardIdHash.substring(0, Math.min(8, cardIdHash.length())) + "…" : "MISSING");

        if (electionId == null || electionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "electionId is required"));
        }
        if (terminalId == null || terminalId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "terminalId is required"));
        }
        if (cardIdHash == null || cardIdHash.isBlank()) {
            log.warn("[TAP] ⚠️  cardIdHash missing — voter geo fields will be null; " +
                    "update firmware to include cardIdHash in tap payload");
        }

        String sessionToken = java.util.UUID.randomUUID().toString();

        VotingSession session = new VotingSession();
        session.setElectionId(java.util.UUID.fromString(electionId));
        session.setTerminalId(terminalId);
        session.setSessionTokenHash(com.evoting.model.AuditLog.sha256(sessionToken));
        session.setExpiresAt(java.time.OffsetDateTime.now().plusMinutes(10));
        session.setUsed(false);

        // BUG-4 FIX: bind voter card to this session for cross-check in processVote()
        if (cardIdHash != null && !cardIdHash.isBlank()) {
            session.setCardIdHash(cardIdHash);
        }

        // BUG-2 FIX: resolve pollingUnitId from TerminalRegistry (not hardcoded 4)
        terminalRegistryRepo.findByTerminalIdAndActiveTrue(terminalId).ifPresent(reg -> {
            if (reg.getPollingUnitId() != null) {
                session.setPollingUnitId(reg.getPollingUnitId().longValue());
            }
        });

        // BUG-3 FIX: resolve stateId + lgaId from voter's polling unit chain
        // Voter's own geo overrides the terminal's registered unit (supports assisted voting)
        if (cardIdHash != null && !cardIdHash.isBlank()) {
            voterRegistryRepo.findByCardIdHash(cardIdHash).ifPresentOrElse(
                voter -> {
                    if (voter.getPollingUnit() != null) {
                        session.setPollingUnitId(voter.getPollingUnit().getId());
                    }
                    if (voter.getLga() != null) {
                        session.setLgaId(voter.getLga().getId());
                        if (voter.getState() != null) {
                            session.setStateId(voter.getState().getId());
                        } else {
                            log.warn("[TAP] ⚠️  voter {} has LGA but null State — check DB geo chain", cardIdHash);
                        }
                    } else {
                        log.warn("[TAP] ⚠️  voter {} has null LGA — geo fields will be null", cardIdHash);
                    }
                },
                () -> log.warn("[TAP] ⚠️  No voter found for cardIdHash {} — geo from terminal only",
                        cardIdHash.substring(0, Math.min(8, cardIdHash.length())))
            );
        }

        if (session.getPollingUnitId() == null) {
            log.error("[TAP] pollingUnitId is still null after all lookups for terminalId={}", terminalId);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Terminal not fully configured — set pollingUnitId via admin dashboard"));
        }

        sessionRepo.save(session);

        log.info("[TAP] ✅ Session created — pollingUnitId={} lgaId={} stateId={}",
                session.getPollingUnitId(), session.getLgaId(), session.getStateId());

        return ResponseEntity.ok(Map.of("sessionToken", sessionToken));
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/terminal/biometric-reset-log
    //  Called by ESP32 firmware immediately after a successful on-card
    //  INS_ADMIN_RESET_BIOMETRIC_TRIES (0x33) call — the reset itself has
    //  already happened locally on the JavaCard by the time this fires.
    //  This is a best-effort audit record, NOT an authorization gate:
    //  firmware does not block the officer's UI on this call succeeding,
    //  and this endpoint never has the power to undo or veto a reset that
    //  already took place on the card.
    // ─────────────────────────────────────────────────────────────────────────
    @Autowired private com.evoting.service.CardManagementService cardManagementService;

    @PostMapping("/biometric-reset-log")
    public ResponseEntity<?> logBiometricReset(HttpServletRequest request,
                                                @RequestBody Map<String, String> body) {
        // Terminal identity verified upstream by TerminalAuthFilter (ECDSA app-layer signing),
        // same as /vote and /tap — no separate admin JWT exists on the terminal to check here.
        String terminalId = request.getHeader("X-Terminal-Id");
        String cardIdHash = body.get("cardIdHash");
        String electionIdStr = body.get("electionId");

        if (terminalId == null || terminalId.isBlank()) {
            log.warn("[BIOMETRIC-RESET-LOG] Rejected: missing X-Terminal-Id header");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Terminal not identified"));
        }
        if (cardIdHash == null || cardIdHash.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cardIdHash is required"));
        }

        UUID electionId = null;
        if (electionIdStr != null && !electionIdStr.isBlank()) {
            try {
                electionId = UUID.fromString(electionIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("[BIOMETRIC-RESET-LOG] Bad electionId '{}' from terminal={}, logging without it", electionIdStr, terminalId);
            }
        }

        cardManagementService.resetBiometricTries(cardIdHash, electionId, "TERMINAL:" + terminalId);
        log.info("[BIOMETRIC-RESET-LOG] card={} terminal={}", cardIdHash, terminalId);
        return ResponseEntity.ok().build();
    }

    // ── ADMIN DASHBOARD ENDPOINTS ─────────────────────────────────────────────

    @GetMapping("/all")
    public ResponseEntity<?> getAllTerminals() {
        return ResponseEntity.ok(terminalHeartbeatRepo.findAll());
    }


    // ── UTILITY METHODS ───────────────────────────────────────────────────────

    private String extractTerminalIdFromCert(HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs != null && certs.length > 0) {
            return certs[0].getSubjectX500Principal().getName();
        }
        return null;
    }

    private String decryptAESGCM(String base64Encrypted) throws Exception {
        if (base64Encrypted == null) throw new IllegalArgumentException("Payload is null");

        // Use the dynamically loaded environment variable instead of the hardcoded key
        byte[] dynamicAesKey = Base64.getDecoder().decode(aesKeyBase64);

        byte[] packageBytes = Base64.getDecoder().decode(base64Encrypted);
        byte[] iv = new byte[12];
        System.arraycopy(packageBytes, 0, iv, 0, 12);
        int cipherTextLength = packageBytes.length - 12;
        byte[] cipherTextWithTag = new byte[cipherTextLength];
        System.arraycopy(packageBytes, 12, cipherTextWithTag, 0, cipherTextLength);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(dynamicAesKey, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
        return new String(cipher.doFinal(cipherTextWithTag));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/terminal/config
    //  Called by ESP32-S3 on boot via fetchElectionConfig().
    //  Returns the active election details + livenessMode so the firmware
    //  knows whether to send BURST: (passive) or ACTIVE: (challenge) to the CAM.
    // ─────────────────────────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired
    private com.evoting.service.BiometricService biometricService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.evoting.repository.TerminalRegistryRepository terminalRegistryRepo;

    @GetMapping("/config")
    public ResponseEntity<?> getTerminalConfig(HttpServletRequest request) {
        String terminalId = request.getHeader("X-Terminal-Id");
        if (terminalId == null || terminalId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "X-Terminal-Id header required"));
        }

        // Find active election
        java.util.List<com.evoting.model.Election> active =
                electionRepo.findByStatus(com.evoting.model.Election.ElectionStatus.ACTIVE);

        if (active.isEmpty()) {
            log.warn("[CONFIG] Terminal {} requested config — no ACTIVE election found", terminalId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No active election assigned to this terminal"));
        }

        com.evoting.model.Election election = active.get(0);

        // Resolve polling unit for this terminal
        Integer pollingUnitId = terminalRegistryRepo
                .findByTerminalIdAndActiveTrue(terminalId)
                .map(t -> t.getPollingUnitId())
                .orElse(null);

        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("electionId",    election.getId().toString());
        config.put("electionTitle", election.getName());
        config.put("electionType",  election.getType());
        config.put("closingTime",   election.getEndTime().toString());
        config.put("pollingUnitId", pollingUnitId != null ? pollingUnitId : 0);
        // NEW: tell firmware which liveness model to use
        config.put("livenessMode",  biometricService.getLivenessMode());

        log.info("[CONFIG] Terminal {} → election={} liveness={}",
                terminalId, election.getName(), biometricService.getLivenessMode());

        return ResponseEntity.ok(config);
    }
}
