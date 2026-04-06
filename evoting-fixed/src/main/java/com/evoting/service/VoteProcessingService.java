package com.evoting.service;
import com.evoting.dto.*;
import com.evoting.exception.EvotingAuthException;
import com.evoting.exception.InvalidSessionException;
import com.evoting.model.*;
import com.evoting.repository.*;
import com.evoting.security.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service @Slf4j
public class VoteProcessingService {

    @Autowired private CryptoService            crypto;
    @Autowired private VotingSessionRepository  sessionRepo;
    @Autowired private VoterRegistryRepository  voterRepo;
    @Autowired private BallotBoxRepository      ballotRepo;
    @Autowired private IdempotencyKeyRepository idempotencyRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private TallyService             tallyService;
    @Autowired private ObjectMapper             mapper;
    @Autowired private AnomalyDetectionService  anomalyService;

    @Transactional
    public VoteReceiptDTO processVote(String encryptedPayload) throws Exception {
        // Idempotency guard — detect bit-for-bit retries before decryption
        String payloadHash = AuditLog.sha256(encryptedPayload);
        IdempotencyKey existing = idempotencyRepo.findByPayloadHash(payloadHash).orElse(null);
        if (existing != null) {
            log.info("Idempotent retry detected for hash {}", payloadHash);
            return new VoteReceiptDTO(
                    existing.getTransactionId(),
                    crypto.encrypt(("ACK:" + existing.getTransactionId()).getBytes()),
                    "Vote already recorded. Receipt re-issued.");
        }

        byte[]        raw    = crypto.decrypt(encryptedPayload);
        // Fix B-07: packet is now a typed DTO — NullPointerException on missing fields
        // is caught by @Valid in VoteController before reaching here
        VotePacketDTO packet = mapper.readValue(raw, VotePacketDTO.class);
        String tokenHash = AuditLog.sha256(packet.getSessionToken());

        VotingSession session = sessionRepo.findBySessionTokenHashAndUsedFalse(tokenHash)
                .orElseThrow(() -> new InvalidSessionException("Invalid or already used session"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new InvalidSessionException("Session has expired");
        if (ballotRepo.existsBySessionTokenHash(tokenHash))
            throw new InvalidSessionException("Vote already submitted for this session");

        // Mark session used before writing ballot (prevents concurrent double-submit)
        session.setUsed(true);
        sessionRepo.save(session);

        // Atomic mark-as-voted + card lock
        int updated = voterRepo.markAsVotedAndLock(packet.getCardIdHash(), session.getElectionId());
        if (updated == 0)
            throw new InvalidSessionException("Voter not found or already voted");

        // Fix B-04: Verify ECDSA burn-proof from setVoted() APDU.
        // The applet signs the VoterID (cardIdHash) with its private key when the card is burned.
        // This provides a cryptographic proof that the physical card was burned, enabling
        // cross-terminal double-vote detection even before the server sync completes.
        VoterRegistry voter = voterRepo
                .findByCardIdHashAndElectionId(packet.getCardIdHash(), session.getElectionId())
                .orElseThrow(() -> new InvalidSessionException("Voter record not found after lock"));

        boolean burnValid = crypto.verifyCardSignature(
                voter.getVoterPublicKey(),
                packet.getCardIdHash(),        // VoterID that was signed by setVoted()
                packet.getCardBurnProof());
        if (!burnValid) {
            auditLog.log("VOTE_FAIL_BURN_PROOF", session.getTerminalId(),
                    "Card=" + packet.getCardIdHash());
            throw new InvalidSessionException("Card burn proof verification failed");
        }

        String voteHash      = AuditLog.sha256(
                packet.getCandidateId() + tokenHash + OffsetDateTime.now());
        String transactionId = AuditLog.sha256(voteHash + session.getElectionId())
                .substring(0, 16).toUpperCase();

        ballotRepo.save(BallotBox.builder()
                .electionId(session.getElectionId())
                .candidateId(UUID.fromString(packet.getCandidateId()))
                .voteHash(voteHash)
                .transactionId(transactionId)
                .sessionTokenHash(tokenHash)
                .terminalId(session.getTerminalId())
                .stateId(session.getStateId())
                .lgaId(session.getLgaId())
                .pollingUnitId(session.getPollingUnitId())
                .castAt(OffsetDateTime.now())
                .build());

        idempotencyRepo.save(IdempotencyKey.builder()
                .payloadHash(payloadHash)
                .transactionId(transactionId)
                .build());

        tallyService.incrementTally(session.getElectionId(), packet.getCandidateId());
        if (session.getStateId() != null)
            tallyService.incrementStateTally(session.getElectionId(),
                    session.getStateId(), packet.getCandidateId());
        tallyService.updateMerkleRoot(session.getElectionId(), voteHash);

        // V2: Record vote for anomaly detection (rate monitoring)
        anomalyService.recordVote(session.getTerminalId());

        auditLog.log("VOTE_CAST", session.getTerminalId(),
                "TxID=" + transactionId + " | card LOCKED | burn-proof VERIFIED");

        return new VoteReceiptDTO(
                transactionId,
                crypto.encrypt(("ACK:" + transactionId).getBytes()),
                "Vote recorded. Card locked.");
    }

    /** Purge idempotency keys older than 24 hours — runs nightly at 02:00 */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeOldIdempotencyKeys() {
        int deleted = idempotencyRepo.deleteOlderThan(OffsetDateTime.now().minusHours(24));
        log.info("Purged {} expired idempotency keys", deleted);
    }

    /**
     * Fix B-13: Purge expired voting sessions nightly at 03:00.
     * Prevents unbounded table growth in long-running elections.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = sessionRepo.deleteByExpiresAtBefore(
                OffsetDateTime.now().minusHours(24));
        log.info("Purged {} expired voting sessions", deleted);
    }
}
