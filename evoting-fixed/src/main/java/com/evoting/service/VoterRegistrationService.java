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
        if (voterRepo.findByCardIdHashAndElectionId(dto.getCardIdHash(), dto.getElectionId()).isPresent())
            throw new EvotingAuthException("Card already registered for this election");

        PollingUnit pu = pollingUnitRepo.findById(dto.getPollingUnitId())
            .orElseThrow(() -> new IllegalArgumentException("Polling unit not found: " + dto.getPollingUnitId()));

        String votingId = votingIdService.generate(pu);

        VoterRegistry voter = VoterRegistry.builder()
            .electionId(dto.getElectionId())
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
