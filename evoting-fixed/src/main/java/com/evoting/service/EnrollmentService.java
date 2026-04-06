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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages the terminal-side voter enrollment workflow.
 *
 * Fix B-01: Implements GET /terminal/pending_enrollment + POST /terminal/enrollment.
 * Fix B-05: Generates a unique 16-byte per-card SCP03 static key for each enrollment.
 *
 * Flow:
 *  1. Admin queues enrollment via /api/admin/enrollment/queue.
 *     Server generates 16-byte staticKey, stores Base64(key) in enrollment_queue,
 *     stores SHA-256(key) in the same row for audit.
 *  2. Terminal GETs pending enrollment — receives record including raw cardStaticKey.
 *  3. Terminal personalises card: first 16 bytes of the 564-byte personalize APDU = staticKey.
 *  4. Terminal POSTs completion result with cardIdHash.
 *  5. Service creates voter_registry row, zeroes raw key in enrollment_queue,
 *     stores SHA-256(key) in voter_registry.card_static_key_hash.
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

    /**
     * Admin queues an enrollment record for a terminal to process.
     * Generates a unique 16-byte per-card SCP03 static key.
     */
    @Transactional
    public EnrollmentQueue queueEnrollment(EnrollmentQueueRequestDTO dto, String queuedBy) {
        // Validate polling unit exists
        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Polling unit not found: " + dto.getPollingUnitId()));

        // Fix B-05: generate unique per-card 16-byte SCP03 static key
        byte[] staticKey = new byte[16];
        RNG.nextBytes(staticKey);
        String staticKeyB64  = Base64.getEncoder().encodeToString(staticKey);
        String staticKeyHash = AuditLog.sha256(staticKeyB64);

        EnrollmentQueue record = EnrollmentQueue.builder()
                .terminalId(dto.getTerminalId())
                .electionId(dto.getElectionId())
                .pollingUnitId(dto.getPollingUnitId())
                .voterPublicKey(dto.getVoterPublicKey())
                .encryptedDemographic(dto.getEncryptedDemographic())
                .cardStaticKey(staticKeyB64)
                .cardStaticKeyHash(staticKeyHash)
                .status("PENDING")
                .build();

        EnrollmentQueue saved = enrollmentRepo.save(record);

        auditLog.log("ENROLLMENT_QUEUED", queuedBy,
                "Terminal=" + dto.getTerminalId()
                        + " | PU=" + pu.getName()
                        + " | Election=" + dto.getElectionId());

        log.info("Enrollment queued for terminal {} PU {}", dto.getTerminalId(), pu.getName());
        return saved;
    }

    /**
     * Terminal GETs its next pending enrollment record.
     * Returns null (404) if no pending record exists for this terminal.
     */
    @Transactional(readOnly = true)
    public TerminalEnrollmentRecordDTO getPendingEnrollment(String terminalId) {
        return enrollmentRepo
                .findFirstByTerminalIdAndStatusOrderByCreatedAtAsc(terminalId, "PENDING")
                .map(r -> new TerminalEnrollmentRecordDTO(
                        r.getId(),
                        r.getElectionId(),
                        r.getPollingUnitId(),
                        r.getVoterPublicKey(),
                        r.getEncryptedDemographic(),
                        r.getCardStaticKey()   // raw key delivered once to terminal
                ))
                .orElse(null);
    }

    /**
     * Terminal reports successful card write.
     * Creates voter_registry row, zeroes the raw static key, writes audit trail.
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
                .cardStaticKeyHash(record.getCardStaticKeyHash())   // B-05: key hash for audit
                .hasVoted(false)
                .cardLocked(false)
                .build();

        voterRepo.save(voter);

        // Write card status log REGISTRATION event
        cardLogRepo.save(new CardStatusLog(
                dto.getCardIdHash(), record.getElectionId(),
                CardEvent.REGISTRATION, terminalId));

        // Zero the raw static key and mark completed
        enrollmentRepo.markCompleted(record.getId(), dto.getCardIdHash(), votingId);

        auditLog.log("ENROLLMENT_COMPLETED", terminalId,
                "VotingID=" + votingId
                        + " | Card=" + dto.getCardIdHash()
                        + " | PU=" + pu.getName());

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
}
