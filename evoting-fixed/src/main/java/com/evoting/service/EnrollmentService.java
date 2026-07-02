package com.evoting.service;

import com.evoting.dto.*;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.*;
import com.evoting.model.CardStatusLog.CardEvent;
import com.evoting.repository.*;
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

@Service @Slf4j
public class EnrollmentService {

    @Autowired private EnrollmentQueueRepository enrollmentRepo;
    @Autowired private VoterRegistryRepository   voterRepo;
    @Autowired private PollingUnitRepository     pollingUnitRepo;
    @Autowired private CardStatusLogRepository   cardLogRepo;
    @Autowired private AuditLogService           auditLog;
    @Autowired private VotingIdService           votingIdService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final SecureRandom RNG = new SecureRandom();

    // ── Internal Cryptography Utilities ───────────────────────────────────────

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String sha256Base64(byte[] input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hexToBase64(String hexHash) {
        if (hexHash == null || hexHash.isEmpty()) {
            return Base64.getEncoder().encodeToString(new byte[32]);
        }
        byte[] raw = HexFormat.of().parseHex(hexHash);
        return Base64.getEncoder().encodeToString(raw);
    }

    // ── Production Pipeline: Unified Enrollment ───────────────────────────────

    /**
     * STAGE 1 to STAGE 2 MIGRATION:
     * Takes Demographics and Target Location from the React unified modal,
     * generates card keys, deletes the volatile scan, and stages the queue.
     */
    @Transactional
    public EnrollmentQueueResult unifiedQueueEnrollment(UnifiedEnrollmentDTO dto, String queuedBy) {
        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException("Polling unit not found: " + dto.getPollingUnitId()));

        // 1. Generate 16-byte per-card SCP03 static key
        byte[] staticKey = new byte[16]; RNG.nextBytes(staticKey);
        String staticKeyB64 = Base64.getEncoder().encodeToString(staticKey);
        String staticKeyHash = AuditLog.sha256(staticKeyB64);

        // 2. Generate 32-byte admin token
        byte[] rawAdminToken = new byte[32]; RNG.nextBytes(rawAdminToken);
        String rawAdminTokenB64 = Base64.getEncoder().encodeToString(rawAdminToken);
        String adminTokenHashHex = sha256Hex(rawAdminToken);
        String adminTokenHashB64 = sha256Base64(rawAdminToken);

        // 3. Stage Demographics safely inside the Queue record (Not in the permanent registry!)
        String demoJson = String.format("{\"firstName\":\"%s\",\"surname\":\"%s\",\"dob\":\"%s\",\"gender\":\"%s\"}",
                dto.getFirstName(), dto.getSurname(), dto.getDateOfBirth(), dto.getGender());

        EnrollmentQueue record = EnrollmentQueue.builder()
                .terminalId(dto.getTerminalId())
                .pollingUnitId(dto.getPollingUnitId())
                .voterPublicKey("PENDING_FROM_TERMINAL")
                .encryptedDemographic(demoJson)
                .cardStaticKey(staticKeyB64)
                .cardStaticKeyHash(staticKeyHash)
                .adminTokenHash(adminTokenHashHex)
                .status("PENDING")
                .build();

        EnrollmentQueue saved = enrollmentRepo.save(record);

        // 4. Delete the initial Stage 1 volatile scan
        jdbcTemplate.update("DELETE FROM pending_registrations WHERE card_id_hash = ?", dto.getCardIdHash());

        auditLog.log("UNIFIED_ENROLLMENT_QUEUED", queuedBy, 
                "Terminal=" + dto.getTerminalId() + " | PU=" + pu.getName() + " | EnrollmentId=" + saved.getId());

        log.info("Unified Enrollment staged for terminal {} PU {} enrollmentId={}", 
                dto.getTerminalId(), pu.getName(), saved.getId());

        return new EnrollmentQueueResult(saved, rawAdminTokenB64, adminTokenHashB64);
    }

    // ── Legacy Standard Queue (Kept for backwards compatibility) ──────────────
    @Transactional
    public EnrollmentQueueResult queueEnrollment(EnrollmentQueueRequestDTO dto, String queuedBy) {
        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException("Polling unit not found: " + dto.getPollingUnitId()));

        byte[] staticKey = new byte[16]; RNG.nextBytes(staticKey);
        String staticKeyB64 = Base64.getEncoder().encodeToString(staticKey);
        String staticKeyHash = AuditLog.sha256(staticKeyB64);

        byte[] rawAdminToken = new byte[32]; RNG.nextBytes(rawAdminToken);
        String rawAdminTokenB64 = Base64.getEncoder().encodeToString(rawAdminToken);
        String adminTokenHashHex = sha256Hex(rawAdminToken);
        String adminTokenHashB64 = sha256Base64(rawAdminToken);

        EnrollmentQueue record = EnrollmentQueue.builder()
                .terminalId(dto.getTerminalId())
                .electionId(dto.getElectionId())
                .pollingUnitId(dto.getPollingUnitId())
                .voterPublicKey(dto.getVoterPublicKey())
                .encryptedDemographic(dto.getEncryptedDemographic())
                .cardStaticKey(staticKeyB64)
                .cardStaticKeyHash(staticKeyHash)
                .adminTokenHash(adminTokenHashHex)
                .status("PENDING")
                .build();

        EnrollmentQueue saved = enrollmentRepo.save(record);
        auditLog.log("ENROLLMENT_QUEUED", queuedBy, "EnrollmentId=" + saved.getId());
        return new EnrollmentQueueResult(saved, rawAdminTokenB64, adminTokenHashB64);
    }

    // ── Terminal Fetch ────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public TerminalEnrollmentRecordDTO getPendingEnrollment(String terminalId) {
        return enrollmentRepo
                .findFirstByTerminalIdAndStatusOrderByCreatedAtAsc(terminalId, "PENDING")
                .map(r -> {
                    String adminTokenHashB64 = hexToBase64(r.getAdminTokenHash());
                    return new TerminalEnrollmentRecordDTO(
                            r.getId(),
                            r.getElectionId(),
                            r.getPollingUnitId(),
                            r.getVoterPublicKey(),
                            r.getEncryptedDemographic(),
                            r.getCardStaticKey(),
                            adminTokenHashB64
                    );
                })
                .orElse(null);
    }

  // ── Cryptographic Commit (Hardware Callback) ──────────────────────────────
    @Transactional
    public VoterRegistrationResponseDTO completeEnrollment(EnrollmentResultDTO dto, String terminalId) {
        EnrollmentQueue record = enrollmentRepo.findById(dto.getEnrollmentId())
                .orElseThrow(() -> new IllegalArgumentException("Enrollment record not found: " + dto.getEnrollmentId()));

        if (!record.getTerminalId().equals(dto.getTerminalId()))
            throw new EvotingAuthException("Terminal mismatch for enrollment record");

        if ("COMPLETED".equals(record.getStatus()))
            throw new IllegalStateException("Enrollment already completed");

        // V8 Permanent Identity: Check the global registry
        if (voterRepo.findByCardIdHash(dto.getCardIdHash()).isPresent())
            throw new EvotingAuthException("Card is already registered in the permanent global registry");

        PollingUnit pu = pollingUnitRepo.findById(record.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException("Polling unit not found"));

        // Generate official Identity
        String votingId = votingIdService.generate(pu);

        // 💥 THE FIX: Capture the real ECDSA key sent by the ESP32 hardware
        String resolvedPublicKey = (dto.getVoterPublicKey() != null 
                && !dto.getVoterPublicKey().startsWith("PENDING"))
                ? dto.getVoterPublicKey()
                : record.getVoterPublicKey();

        // 100% Clean Insertion. NO placeholder overwriting.
        VoterRegistry voter = VoterRegistry.builder()
                .votingId(votingId)
                .cardIdHash(dto.getCardIdHash())
                .voterPublicKey(resolvedPublicKey) // <-- Now saving the REAL key!
                .encryptedDemographic(record.getEncryptedDemographic()) 
                .pollingUnit(pu)
                .cardStaticKeyHash(record.getCardStaticKeyHash())
                .adminTokenHash(record.getAdminTokenHash())
                .hasVoted(false)
                .cardLocked(false)
                .build();

        voterRepo.save(voter);

        cardLogRepo.save(new CardStatusLog(dto.getCardIdHash(), record.getElectionId(), CardEvent.REGISTRATION, terminalId));
        enrollmentRepo.markCompleted(record.getId(), dto.getCardIdHash(), votingId);

        auditLog.log("ENROLLMENT_COMPLETED", terminalId, "VotingID=" + votingId + " | Card=" + dto.getCardIdHash());

        return new VoterRegistrationResponseDTO(votingId, pu.getName(), pu.getLga().getName(), pu.getLga().getState().getName(), "Voter enrolled permanently. Voting ID: " + votingId);
    }
    
    // ── Admin Monitoring ──────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public java.util.List<EnrollmentQueue> listPendingEnrollments(java.util.UUID electionId) {
        return enrollmentRepo.findByElectionIdAndStatus(electionId, "PENDING");
    }

    // ── Result Wrapper ────────────────────────────────────────────────────────
    public record EnrollmentQueueResult(
            EnrollmentQueue record,
            String rawAdminTokenB64,    // one-time; return to admin, do not persist
            String adminTokenHashB64    // what the terminal receives
    ) {}
}
