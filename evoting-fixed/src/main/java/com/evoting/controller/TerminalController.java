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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.evoting.model.TerminalHeartbeat;
import com.evoting.repository.TerminalHeartbeatRepository;
import com.evoting.service.VoteProcessingService;
import com.evoting.dto.VoteReceiptDTO;
import com.evoting.exception.InvalidSessionException;

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
    // NEW: The WebSocket Megaphone!
    @Autowired private SimpMessagingTemplate messagingTemplate;
    // --> ADD THIS LINE RIGHT HERE! <--
    @Autowired private TerminalHeartbeatRepository terminalHeartbeatRepo;

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

    // --- AES Key (Must match the ESP32 firmware) ---
    private static final byte[] BACKEND_AES_KEY = {
            0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,
            0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0x1A,0x1B,0x1C,0x1D,0x1E,0x1F,0x20
    };

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

    // ... your other methods (/heartbeat, /vote, /enrollment) ...

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
        byte[] packageBytes = Base64.getDecoder().decode(base64Encrypted);
        byte[] iv = new byte[12];
        System.arraycopy(packageBytes, 0, iv, 0, 12);
        int cipherTextLength = packageBytes.length - 12;
        byte[] cipherTextWithTag = new byte[cipherTextLength];
        System.arraycopy(packageBytes, 12, cipherTextWithTag, 0, cipherTextLength);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(BACKEND_AES_KEY, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
        return new String(cipher.doFinal(cipherTextWithTag));
    }
}