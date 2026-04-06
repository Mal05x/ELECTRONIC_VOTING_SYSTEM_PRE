package com.evoting.service;

import com.evoting.dto.CommitRegistrationDTO;
import com.evoting.model.*;
import com.evoting.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * RegistrationService — two-step terminal-initiated voter registration.
 *
 * Step 1 (terminal):  POST /api/terminal/pending-registration
 *   Terminal reads JCOP4 card → sends cardIdHash + voterPublicKey + pollingUnitId
 *   Creates a pending_registrations record (status=AWAITING_DEMOGRAPHICS)
 *
 * Step 2 (admin dashboard): POST /api/admin/voters/commit-registration
 *   Admin selects the pending card, fills in firstName/surname/dob/gender
 *   Backend encrypts demographics, creates voter_registry + voter_demographics
 *   Generates voting ID (e.g. KD/IG/001/0042)
 */
@Service @Slf4j
public class RegistrationService {

    @Autowired private PendingRegistrationRepository pendingRepo;
    @Autowired private VoterRegistryRepository       voterRepo;
    @Autowired private PollingUnitRepository         puRepo;
    @Autowired private DemographicsService           demoService;
    @Autowired private AuditLogService               auditLog;
    @Autowired private ObjectMapper                  mapper;

    // ── Step 1: Terminal initiates ────────────────────────────────────────
    @Transactional
    public PendingRegistration initiateFromTerminal(String terminalId,
                                                    Long pollingUnitId,
                                                    String cardIdHash,
                                                    String voterPublicKey) {
        // Reject if card already registered
        if (voterRepo.findByCardIdHashAndElectionId(cardIdHash, null).isPresent() ||
                voterRepo.findByCardIdHash(cardIdHash).isPresent()) {
            throw new IllegalStateException("Card already registered: " + cardIdHash);
        }

        // Reject if already pending
        pendingRepo.findByCardIdHash(cardIdHash).ifPresent(existing -> {
            if ("AWAITING_DEMOGRAPHICS".equals(existing.getStatus())) {
                throw new IllegalStateException(
                        "Card already has a pending registration. Ask admin to commit or cancel it.");
            }
        });

        PollingUnit pu = puRepo.findById(pollingUnitId)
                .orElseThrow(() -> new IllegalArgumentException("Polling unit not found: " + pollingUnitId));

        PendingRegistration pending = PendingRegistration.builder()
                .terminalId(terminalId)
                .pollingUnitId(pollingUnitId)
                .cardIdHash(cardIdHash)
                .voterPublicKey(voterPublicKey)
                .status("AWAITING_DEMOGRAPHICS")
                .build();

        pending = pendingRepo.save(pending);

        auditLog.log("PENDING_REGISTRATION_CREATED", terminalId,
                "CardHash=" + cardIdHash + " PollingUnit=" + pu.getName());

        log.info("[REGISTRATION] Pending created by terminal {} for card {}",
                terminalId, cardIdHash);
        return pending;
    }

    // ── Step 2: Admin commits with demographics ───────────────────────────
    @Transactional
    public Map<String, Object> commitRegistration(UUID pendingId,
                                                  CommitRegistrationDTO dto,
                                                  String committedBy) {
        PendingRegistration pending = pendingRepo.findById(pendingId)
                .orElseThrow(() -> new IllegalArgumentException("Pending registration not found"));

        if (!"AWAITING_DEMOGRAPHICS".equals(pending.getStatus()))
            throw new IllegalStateException("Registration is not in AWAITING_DEMOGRAPHICS state");

        if (pending.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalStateException("Pending registration has expired — ask terminal to re-scan");

        PollingUnit pu = puRepo.findById(pending.getPollingUnitId()).orElseThrow();

        // Generate voting ID: STATE_CODE/LGA_CODE/PU_SEQ/VOTER_SEQ
        String votingId = generateVotingId(pu);

        // Create permanent voter_registry record (no election_id — permanent identity)
        VoterRegistry voter = VoterRegistry.builder()
                .electionId(null)  // permanent — eligible for any election in scope
                .cardIdHash(pending.getCardIdHash())
                .voterPublicKey(pending.getVoterPublicKey())
                .votingId(votingId)
                .pollingUnit(pu)
                .firstName(dto.getFirstName())
                .surname(dto.getSurname())
                .enrolled(false)    // not enrolled yet — terminal will do biometrics
                .build();

        voter = voterRepo.save(voter);

        // Encrypt and store demographics
        Map<String, String> demoData = new LinkedHashMap<>();
        demoData.put("firstName", dto.getFirstName());
        demoData.put("surname",   dto.getSurname());
        demoData.put("dob",       dto.getDateOfBirth());
        demoData.put("gender",    dto.getGender());

        String demoJson = toJson(demoData);
        demoService.storeEncrypted(voter.getId(), demoJson);

        // Mark pending as committed
        pending.setStatus("COMMITTED");
        pending.setCommittedBy(committedBy);
        pending.setCommittedAt(OffsetDateTime.now());
        pending.setVoterRegistryId(voter.getId());
        pendingRepo.save(pending);

        auditLog.log("VOTER_REGISTERED", committedBy,
                "VotingId=" + votingId + " PollingUnit=" + pu.getName() +
                        " Terminal=" + pending.getTerminalId());

        log.info("[REGISTRATION] Committed {} by {}", votingId, committedBy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votingId",    votingId);
        result.put("pollingUnit", pu.getName());
        result.put("lga",         pu.getLga().getName());
        result.put("state",       pu.getLga().getState().getName());
        result.put("enrolled",    false);
        result.put("message",     "Voter registered. Present card at terminal for biometric enrollment.");
        return result;
    }

    // ── Cancel a pending registration ─────────────────────────────────────
    @Transactional
    public void cancelPending(UUID pendingId, String cancelledBy) {
        PendingRegistration pending = pendingRepo.findById(pendingId).orElseThrow();
        pending.setStatus("CANCELLED");
        pendingRepo.save(pending);
        auditLog.log("PENDING_REGISTRATION_CANCELLED", cancelledBy,
                "PendingId=" + pendingId + " CardHash=" + pending.getCardIdHash());
    }

    // ── Voting ID generation ──────────────────────────────────────────────
    private synchronized String generateVotingId(PollingUnit pu) {
        String stateCode = pu.getLga().getState().getCode();
        String lgaCode   = String.format("%02d", pu.getLga().getId() % 100);
        String puCode    = String.format("%03d", pu.getId() % 1000);
        long   seq       = voterRepo.countByPollingUnitId(pu.getId()) + 1;
        return stateCode + "/" + lgaCode + "/" + puCode + "/" + String.format("%04d", seq);
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { throw new RuntimeException("JSON serialization failed", e); }
    }
}
