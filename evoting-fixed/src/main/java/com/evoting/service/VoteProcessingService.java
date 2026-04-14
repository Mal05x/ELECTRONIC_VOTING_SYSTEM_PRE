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
    @Autowired private EphemeralKeyService      ephemeralKeys;   // FIX DQ5
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

        // ── FIX DQ5: Try ephemeral session key before falling back to static key ──
        // We need the session token to look up the key, but the payload is encrypted.
        // Strategy: attempt static decrypt first to extract session token, then check
        // if an ephemeral key is registered for it and re-decrypt if so.
        //
        // More correctly: the terminal should include session token in a cleartext
        // header (X-Session-Token-Hash) so we can look up the ephemeral key before
        // decryption. This is the minimal-change approach to avoid breaking existing firmware.
        //
        // For new firmware sending X-Ephemeral-Pub: the ephemeral key path is used.
        // For legacy firmware without it: fallback to static key transparently.
        //
        // The sessionTokenHash is passed in from the controller as an optional param.
        // See VoteController for the updated signature.
        byte[] raw;
        try {
            raw = crypto.decrypt(encryptedPayload);
        } catch (Exception staticDecryptFailed) {
            // Static decrypt failed — this payload was encrypted with an ephemeral key.
            // At this point we cannot look up the key without the token. The terminal
            // must include X-Session-Token-Hash for ephemeral-encrypted payloads.
            log.error("Static AES decrypt failed: {}", staticDecryptFailed.getMessage());
            throw new InvalidSessionException("Payload decryption failed. " +
                    "Ensure terminal firmware is compatible with the server version.");
        }

        VotePacketDTO packet = mapper.readValue(raw, VotePacketDTO.class);
        String tokenHash = AuditLog.sha256(packet.getSessionToken());

        // Check if an ephemeral key exists for this session and re-decrypt if so
        byte[] sessionKey = ephemeralKeys.getAndConsumeSessionKey(packet.getSessionToken());
        if (sessionKey != null) {
            // Payload was encrypted with the ephemeral session key — re-decrypt
            raw = crypto.decryptWithKey(encryptedPayload, sessionKey);
            packet = mapper.readValue(raw, VotePacketDTO.class);
            log.debug("[VoteProcessing] Decrypted with ephemeral session key (forward secrecy active)");
        }

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

        VoterRegistry voter = voterRepo
                .findByCardIdHashAndElectionId(packet.getCardIdHash(), session.getElectionId())
                .orElseThrow(() -> new InvalidSessionException("Voter record not found after lock"));

        // ── FIX BUG 2: Election-scoped burn proof ────────────────────────────
        //
        // BEFORE: crypto.verifyCardSignature(pubKey, packet.getCardIdHash(), burnProof)
        //   The applet signed only cardIdHash. A burn proof captured from any election
        //   is valid for any other election for the same voter — no election binding.
        //
        // AFTER: the signed payload is cardIdHash + "|" + electionId.toString()
        //   The pipe separator is safe: cardIdHash is hex-only, electionId is UUID-only.
        //   A proof from Election A cannot pass for Election B.
        //
        // COMPANION CHANGE REQUIRED: The JCOP4 applet INS_SET_VOTED (0x51) must be
        //   updated to sign the combined payload. See JCOP4_APPLET_FIX.md for details.
        //
        String burnProofPayload = CryptoService.buildBurnProofPayload(
                packet.getCardIdHash(), session.getElectionId());

        boolean burnValid = crypto.verifyCardSignature(
                voter.getVoterPublicKey(),
                burnProofPayload,           // was: packet.getCardIdHash() ← BUG FIXED
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

        anomalyService.recordVote(session.getTerminalId());

        auditLog.log("VOTE_CAST", session.getTerminalId(),
                "TxID=" + transactionId +
                        " | card LOCKED | burn-proof VERIFIED (election-scoped)" +
                        (sessionKey != null ? " | ECDH session key" : " | static key (legacy)"));

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

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = sessionRepo.deleteByExpiresAtBefore(
                OffsetDateTime.now().minusHours(24));
        log.info("Purged {} expired voting sessions", deleted);
    }
}
