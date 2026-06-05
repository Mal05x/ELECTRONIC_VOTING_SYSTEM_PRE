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
import com.evoting.model.VotingSession;
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

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticateTerminal(HttpServletRequest request, @RequestBody Map<String, String> body) {
        String terminalSubject = request.getHeader("X-Terminal-Id");
        if (terminalSubject == null || terminalSubject.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid or missing terminal certificate"));
        }

        try {
            String encryptedPayload = body.get("payload");
            String decryptedJson = decryptAESGCM(encryptedPayload);
            log.info("🔓 Terminal {} Authenticated. Payload: {}", terminalSubject, decryptedJson);

            return ResponseEntity.ok(Map.of("sessionToken", UUID.randomUUID().toString()));
        } catch (Exception e) {
            log.error("Authentication decryption failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Decryption failed"));
        }
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
        log.info("[TAP] Card " + payload.get("cardIdHash") + " tapped on " + payload.get("terminalId"));
        
        String terminalId = payload.get("terminalId");
        String electionId = payload.get("electionId"); // We will now send this from the ESP32!
        
        // If no election ID is passed, it's just an Enrollment tap.
        if (electionId == null) {
            return ResponseEntity.ok().build();
        }

        // It is a Voting Tap! Generate a highly secure, one-time session token
        String sessionToken = java.util.UUID.randomUUID().toString();
        
        VotingSession session = new VotingSession();
        session.setElectionId(java.util.UUID.fromString(electionId));
        session.setTerminalId(terminalId);
        session.setPollingUnitId(4L);
        session.setSessionTokenHash(com.evoting.model.AuditLog.sha256(sessionToken));
        session.setExpiresAt(java.time.OffsetDateTime.now().plusMinutes(10));
        session.setUsed(false);
        sessionRepo.save(session);
        
        // Return the secure token to the terminal
        return ResponseEntity.ok(java.util.Map.of("sessionToken", sessionToken));
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
