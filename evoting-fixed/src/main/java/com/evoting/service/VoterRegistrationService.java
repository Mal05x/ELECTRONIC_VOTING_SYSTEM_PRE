package com.evoting.service;
import com.evoting.dto.VoterRegistrationDTO;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.*;
import com.evoting.model.CardStatusLog.CardEvent;
import com.evoting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoterRegistrationService {

    @Autowired private VoterRegistryRepository voterRepo;
    @Autowired private PollingUnitRepository   pollingUnitRepo;
    @Autowired private CardStatusLogRepository cardLogRepo;
    @Autowired private AuditLogService         auditLog;
    @Autowired private VotingIdService         votingIdService;

    /**
     * Registers a voter:
     *  1. Resolves polling unit → LGA → State.
     *  2. Generates INEC-style Voting ID.
     *  3. Saves voter with card in UNLOCKED state.
     *  4. Writes a REGISTRATION event to card_status_log.
     *  5. Writes to audit_log.
     */
    @Transactional
    public VoterRegistry register(VoterRegistrationDTO dto, String registeredBy) {
        // FIX (V29 follow-up): was findByCardIdHashAndElectionId(cardIdHash,
        // dto.getElectionId()). card_id_hash is globally UNIQUE on
        // voter_registry, but this endpoint still wrote a specific
        // election_id per registration (pre-V8 style) — so a card already
        // registered permanently (election_id = NULL, via EnrollmentService
        // or RegistrationService) would NOT be found by this scoped query,
        // the check would pass, and voterRepo.save() below would then fail
        // with a raw DB unique-constraint violation instead of the clean
        // "Card already registered" error this method is supposed to give.
        if (voterRepo.findByCardIdHash(dto.getCardIdHash()).isPresent())
            throw new EvotingAuthException("Card already registered");

        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
            .orElseThrow(() -> new IllegalArgumentException("Polling unit not found: " + dto.getPollingUnitId()));

        String votingId = votingIdService.generate(pu);

        VoterRegistry voter = VoterRegistry.builder()
            // FIX (V29 follow-up): was .electionId(dto.getElectionId()).
            // Every other live registration path (EnrollmentService.
            // completeEnrollment(), RegistrationService.initiateFromTerminal())
            // creates permanent, election-agnostic identity rows per V8 —
            // this endpoint was the one holdout still writing a specific
            // election_id, which nothing downstream has actually read that
            // way since V29 (vote-gating, the admin voters list, and
            // per-state turnout are all keyed on cardIdHash / a separate
            // per-election status table now). Left as a dangling
            // inconsistency it would have been a trap for whoever next
            // touched this file, wondering why registering here "for
            // election B" silently did nothing election-specific.
            .votingId(votingId)
            .cardIdHash(dto.getCardIdHash())
            .voterPublicKey(dto.getVoterPublicKey())
            .encryptedDemographic(dto.getEncryptedDemographic())
            .pollingUnit(pu)
            .hasVoted(false)
            .cardLocked(false)
            .build();

        VoterRegistry saved = voterRepo.save(voter);

        // Write REGISTRATION event to card status log
        cardLogRepo.save(new CardStatusLog(dto.getCardIdHash(), dto.getElectionId(),
            CardEvent.REGISTRATION, registeredBy));

        auditLog.log("VOTER_REGISTERED", registeredBy,
            "VotingID: " + votingId
            + " | PU: "    + pu.getName()
            + " | LGA: "   + pu.getLga().getName()
            + " | State: " + pu.getLga().getState().getName());

        return saved;
    }
}
