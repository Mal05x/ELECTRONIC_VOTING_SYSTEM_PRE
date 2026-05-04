package com.evoting.service;

import com.evoting.dto.*;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.*;
import com.evoting.model.CardStatusLog.CardEvent;
import com.evoting.repository.*;
import com.evoting.model.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages the terminal-side voter enrollment workflow.
 *
 * Flow overview
 * ─────────────
 * 1. SUPER_ADMIN calls POST /api/admin/enrollment/queue.
 *    This method generates:
 *      a) 16-byte per-card SCP03 cardStaticKey  — stored Base64, hash stored for audit.
 *      b) 32-byte raw adminToken                — SHA-256 stored, raw returned ONCE to admin.
 *    Response includes rawAdminToken (Base64) — the admin must store this securely.
 *    It is used later to decommission (lock) the card via INS_LOCK_CARD.
 *
 * 2. Terminal GETs /api/terminal/pending_enrollment.
 *    Receives cardStaticKey (raw Base64) and adminTokenHash (Base64 SHA-256).
 *
 * 3. Terminal personalises card via INS_PERSONALIZE (596-byte APDU):
 *      bytes [0..15]   = cardStaticKey   (raw 16 bytes)
 *      bytes [16..19]  = voter PIN       (entered at terminal, never sent here)
 *      bytes [20..51]  = voterID         = SHA-256(enrollmentId UTF-8)
 *      bytes [52..563] = fingerprintTemplate (captured at terminal)
 *      bytes [564..595]= adminTokenHash  (raw 32 bytes — SHA-256 of rawAdminToken)
 *
 * 4. Terminal also saves cardStaticKey to its local NVS (keyed by SHA-256(cardUID))
 *    so it can derive the session key on every future voting session.
 *
 * 5. Terminal POSTs completion to /api/terminal/enrollment.
 *    Service creates voter_registry row, zeroes raw cardStaticKey in DB.
 *    adminTokenHash is kept for audit (it's already on the card anyway).
 *
 * Card locking (decommissioning):
 *    SUPER_ADMIN supplies rawAdminToken to terminal via secure channel.
 *    Terminal AES-encrypts it under the NFC session key → sends INS_LOCK_CARD.
 *    Applet decrypts, SHA-256s, compares against stored adminTokenHash → lock.
 */
@Service @Slf4j
public class EnrollmentService {

    @Autowired private EnrollmentQueueRepository enrollmentRepo;
    @Autowired private VoterRegistryRepository   voterRepo;
    @Autowired private PollingUnitRepository     pollingUnitRepo;
    @Autowired private CardStatusLogRepository   cardLogRepo;
    @Autowired private AuditLogService           auditLog;
    @Autowired private VotingIdService           votingIdService;

    private static final SecureRandom RNG = new SecureRandom();

    // ── Internal: SHA-256 raw bytes → hex string ──────────────────────────────

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Internal: SHA-256 raw bytes → Base64 string ───────────────────────────
    // Used to transmit adminTokenHash to the terminal.
    // The terminal decodes Base64 → 32 bytes and writes them to the card.

    private static String sha256Base64(byte[] input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // queueEnrollment
    // Called by: POST /api/admin/enrollment/queue  (SUPER_ADMIN + step-up)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Admin queues an enrollment record for a terminal to process.
     *
     * Generates:
     *   a) 16-byte per-card SCP03 cardStaticKey (stored Base64, hash stored for audit)
     *   b) 32-byte raw adminToken (SHA-256 stored in DB, raw returned to caller)
     *
     * @return EnrollmentQueueResult containing the saved record AND the
     *         one-time rawAdminToken (Base64) for the admin to store securely.
     */
    @Transactional
    public EnrollmentQueueResult queueEnrollment(EnrollmentQueueRequestDTO dto,
                                                 String queuedBy) {
        // Validate polling unit exists
        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Polling unit not found: " + dto.getPollingUnitId()));

        // ── a) Generate 16-byte per-card SCP03 static key ────────────────────
        byte[] staticKey     = new byte[16];
        RNG.nextBytes(staticKey);
        String staticKeyB64  = Base64.getEncoder().encodeToString(staticKey);
        String staticKeyHash = AuditLog.sha256(staticKeyB64);

        // ── b) Generate 32-byte admin token ───────────────────────────────────
        // RAW token: returned once to the SUPER_ADMIN, never stored in DB.
        // HASH (SHA-256 of raw bytes): stored in DB and sent to terminal.
        //   → Terminal writes the hash (32 bytes) into card bytes [564..595].
        //   → For card locking: admin supplies raw token → terminal encrypts →
        //     applet decrypts → SHA-256 → compare with stored hash.
        byte[] rawAdminToken     = new byte[32];
        RNG.nextBytes(rawAdminToken);
        String rawAdminTokenB64  = Base64.getEncoder().encodeToString(rawAdminToken);
        String adminTokenHashHex = sha256Hex(rawAdminToken);    // stored in DB
        String adminTokenHashB64 = sha256Base64(rawAdminToken); // sent to terminal

        EnrollmentQueue record = EnrollmentQueue.builder()
                .terminalId(dto.getTerminalId())
                .electionId(dto.getElectionId())
                .pollingUnitId(dto.getPollingUnitId())
                .voterPublicKey(dto.getVoterPublicKey())
                .encryptedDemographic(dto.getEncryptedDemographic())
                .cardStaticKey(staticKeyB64)
                .cardStaticKeyHash(staticKeyHash)
                .adminTokenHash(adminTokenHashHex)  // hex in DB for readability
                .status("PENDING")
                .build();

        EnrollmentQueue saved = enrollmentRepo.save(record);

        auditLog.log("ENROLLMENT_QUEUED", queuedBy,
                "Terminal=" + dto.getTerminalId()
                        + " | PU=" + pu.getName()
                        + " | Election=" + dto.getElectionId()
                        + " | EnrollmentId=" + saved.getId()
                        + " | AdminTokenHashHex=" + adminTokenHashHex);

        log.info("Enrollment queued for terminal {} PU {} enrollmentId={}",
                dto.getTerminalId(), pu.getName(), saved.getId());

        // Return both the saved record and the one-time raw admin token.
        // The controller will include rawAdminTokenB64 in its response.
        return new EnrollmentQueueResult(saved, rawAdminTokenB64, adminTokenHashB64);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getPendingEnrollment
    // Called by: GET /api/terminal/pending_enrollment?terminalId=
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Terminal fetches its next PENDING enrollment record.
     * Returns null (204) if none queued for this terminal.
     *
     * adminTokenHash is now included so the terminal can write it to the card
     * during INS_PERSONALIZE (bytes [564..595] of the 596-byte payload).
     */
    @Transactional(readOnly = true)
    public TerminalEnrollmentRecordDTO getPendingEnrollment(String terminalId) {
        return enrollmentRepo
                .findFirstByTerminalIdAndStatusOrderByCreatedAtAsc(terminalId, "PENDING")
                .map(r -> {
                    // Convert stored hex adminTokenHash → Base64 for terminal wire format.
                    // Terminal Base64-decodes → 32 raw bytes → writes to card.
                    String adminTokenHashB64 = hexToBase64(r.getAdminTokenHash());

                    return new TerminalEnrollmentRecordDTO(
                            r.getId(),
                            r.getElectionId(),
                            r.getPollingUnitId(),
                            r.getVoterPublicKey(),
                            r.getEncryptedDemographic(),
                            r.getCardStaticKey(),     // raw 16-byte key (Base64)
                            adminTokenHashB64         // SHA-256(rawAdminToken) as Base64
                    );
                })
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // completeEnrollment
    // Called by: POST /api/terminal/enrollment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Terminal reports successful card write.
     * Creates voter_registry row, zeroes the raw static key, writes audit trail.
     * adminTokenHash is intentionally kept (not zeroed) — it is already public
     * knowledge (written to the card) and needed for audit / decommission.
     */
    @Transactional
    public VoterRegistrationResponseDTO completeEnrollment(
            EnrollmentResultDTO dto, String terminalId) {

        EnrollmentQueue record = enrollmentRepo.findById(dto.getEnrollmentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Enrollment record not found: " + dto.getEnrollmentId()));

        if (!record.getTerminalId().equals(dto.getTerminalId()))
            throw new EvotingAuthException("Terminal mismatch for enrollment record");

        if ("COMPLETED".equals(record.getStatus()))
            throw new IllegalStateException("Enrollment already completed");

        if (voterRepo.findByCardIdHashAndElectionId(
                dto.getCardIdHash(), record.getElectionId()).isPresent())
            throw new EvotingAuthException("Card already registered for this election");

        PollingUnit pu = pollingUnitRepo.findById(record.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException("Polling unit not found"));

        String votingId = votingIdService.generate(pu);

        VoterRegistry voter = VoterRegistry.builder()
                .electionId(record.getElectionId())
                .votingId(votingId)
                .cardIdHash(dto.getCardIdHash())
                .voterPublicKey(record.getVoterPublicKey())
                .encryptedDemographic(record.getEncryptedDemographic())
                .pollingUnit(pu)
                .cardStaticKeyHash(record.getCardStaticKeyHash())
                .hasVoted(false)
                .cardLocked(false)
                .build();

        voterRepo.save(voter);

        cardLogRepo.save(new CardStatusLog(
                dto.getCardIdHash(), record.getElectionId(),
                CardEvent.REGISTRATION, terminalId));

        // Zero the raw static key; mark completed.
        // adminTokenHash is NOT zeroed — it is already on the card and
        // is needed to verify future decommission requests.
        enrollmentRepo.markCompleted(record.getId(), dto.getCardIdHash(), votingId);

        auditLog.log("ENROLLMENT_COMPLETED", terminalId,
                "VotingID=" + votingId
                        + " | Card=" + dto.getCardIdHash()
                        + " | PU=" + pu.getName()
                        + " | EnrollmentId=" + record.getId());

        return new VoterRegistrationResponseDTO(
                votingId,
                pu.getName(),
                pu.getLga().getName(),
                pu.getLga().getState().getName(),
                "Voter enrolled. Voting ID: " + votingId);
    }

    /** Admin monitoring: list pending enrollments for an election. */
    @Transactional(readOnly = true)
    public java.util.List<EnrollmentQueue> listPendingEnrollments(java.util.UUID electionId) {
        return enrollmentRepo.findByElectionIdAndStatus(electionId, "PENDING");
    }

    // ── Utility: hex string → Base64 ─────────────────────────────────────────
    // adminTokenHash is stored as 64-char hex in the DB for human readability
    // in audit logs. The terminal expects Base64 for wire efficiency.

    private static String hexToBase64(String hexHash) {
        if (hexHash == null || hexHash.isEmpty()) {
            // Should not happen — log a warning upstream if needed.
            return Base64.getEncoder().encodeToString(new byte[32]);
        }
        byte[] raw = HexFormat.of().parseHex(hexHash);
        return Base64.getEncoder().encodeToString(raw);
    }

    // ── Inner result type ─────────────────────────────────────────────────────
    // Carries both the persisted record and the one-time raw admin token.
    // Using a record (Java 16+) keeps this tight without a separate file.

    public record EnrollmentQueueResult(
            EnrollmentQueue record,
            String rawAdminTokenB64,    // one-time; return to admin, do not persist
            String adminTokenHashB64    // what the terminal receives
    ) {}
}
