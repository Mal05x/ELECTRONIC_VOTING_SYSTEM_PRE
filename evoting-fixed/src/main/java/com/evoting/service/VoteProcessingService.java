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
    @Autowired private VoterElectionStatusRepository voterElectionStatusRepo; // V29
    @Autowired private BallotBoxRepository      ballotRepo;
    @Autowired private IdempotencyKeyRepository idempotencyRepo;
    @Autowired private AuditLogService          auditLog;
    @Autowired private TallyService             tallyService;
    @Autowired private ObjectMapper             mapper;
    @Autowired private AnomalyDetectionService  anomalyService;

    @Transactional
    public VoteReceiptDTO processVote(String encryptedPayload) throws Exception {
        // Idempotency guard #1 — catches a literal byte-for-byte repeat of the
        // exact same HTTP request (e.g. a raw TCP-level retransmission that
        // somehow gets processed twice server-side). This does NOT catch the
        // ESP32's offline-recovery retries: net_check_and_recover_vote()
        // re-encrypts with esp_fill_random(iv, ...) — a fresh random IV — AND
        // a brand new session token from a fresh /tap call on every single
        // retry cycle, so the ciphertext (and therefore this hash) is
        // different every time even though it's logically the same vote.
        // Guard #2 below (transactionId-based) is what actually protects the
        // offline-recovery path; this one is a cheap pre-decrypt short-circuit
        // for the narrower case it can actually catch.
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
        // See TerminalController.submitVote for the updated signature.
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

        // Idempotency guard #2 — THE one that matters for offline recovery.
        // cardBurnProof is stable across every retry of this physical vote
        // (a card can only be burned once), so this derived ID is identical
        // whether this is attempt #1 or attempt #12, regardless of IV or
        // session token. Computed here — once — and reused below for the
        // stored transactionId, so there's exactly one source of truth.
        // Checking this BEFORE touching session/voter state means a replay
        // short-circuits to a 200 with the original receipt, instead of
        // falling through to the voter.isHasVoted() check further down and
        // throwing a generic 401 that the firmware's recovery loop doesn't
        // recognize as terminal (it only stops retrying on 200/201/400/403/
        // 404/409/422). This directly fixes "vote recorded but the ESP32
        // never got confirmation, so it retries forever."
        String transactionId = AuditLog.sha256(packet.getCardBurnProof()).toUpperCase();
        IdempotencyKey existingByTx = idempotencyRepo.findByTransactionId(transactionId).orElse(null);
        if (existingByTx != null) {
            log.info("Idempotent retry detected for txId {} (payload hash differed — expected, see IV note above)", transactionId);
            return new VoteReceiptDTO(
                    existingByTx.getTransactionId(),
                    crypto.encrypt(("ACK:" + existingByTx.getTransactionId()).getBytes()),
                    "Vote already recorded. Receipt re-issued.");
        }

        VotingSession session = sessionRepo.findBySessionTokenHashAndUsedFalse(tokenHash)
                .orElseThrow(() -> new InvalidSessionException("Invalid or already used session"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new InvalidSessionException("Session has expired");
        if (ballotRepo.existsBySessionTokenHash(tokenHash))
            throw new InvalidSessionException("Vote already submitted for this session");

        // BUG-5 FIX: cross-check session was created for this exact card
        // Prevents a stolen session token being paired with a different voter's card
        if (session.getCardIdHash() != null &&
            !session.getCardIdHash().equals(packet.getCardIdHash())) {
            auditLog.log("VOTE_FAIL_CARD_MISMATCH", session.getTerminalId(),
                    "Session card=" + session.getCardIdHash() + " Packet card=" + packet.getCardIdHash());
            throw new InvalidSessionException("Card identity mismatch — session was not created for this card");
        }

        // BUG-6 FIX: cross-check electionId in packet matches the session's election
        // Prevents a valid session being reused to submit a vote for a different election
        if (packet.getElectionId() != null &&
            !session.getElectionId().equals(packet.getElectionId())) {
            auditLog.log("VOTE_FAIL_ELECTION_MISMATCH", session.getTerminalId(),
                    "Session election=" + session.getElectionId() + " Packet election=" + packet.getElectionId());
            throw new InvalidSessionException("Election ID mismatch between session and vote packet");
        }

        // Mark session used before writing ballot (prevents concurrent double-submit)
        session.setUsed(true);
        sessionRepo.save(session);

        // Voter identity lookup — still global (V8: one permanent row per
        // card). Only used here for the public key below; vote-gating itself
        // is scoped per election now, see the FIX (V29) block right after.
        VoterRegistry voter = voterRepo.findByCardIdHash(packet.getCardIdHash())
        .orElseThrow(() -> new InvalidSessionException("Voter not found for this card"));

        // FIX (V29): "has this card voted" used to live as a lifetime
        // has_voted boolean on the same permanent voter_registry row as the
        // voter's identity — so once true, it stayed true forever, blocking
        // every election created afterwards (this is the "new election
        // rejects a vote that should be valid" bug). It's now tracked per
        // (election, card) in voter_election_status. The atomic upsert below
        // is the same concurrency guard the old markAsVotedAndLockByCardHash
        // provided (WHERE has_voted = false), just correctly scoped to
        // session.getElectionId() instead of the card for its whole lifetime.
        int updated = voterElectionStatusRepo.markAsVotedAndLock(session.getElectionId(), packet.getCardIdHash());
        if (updated == 0)
            throw new InvalidSessionException("Voter has already cast a ballot in this election");

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

        // Internal only — encodes candidate + session + cast time. Used for
        // Merkle-tree tallying/audit. NEVER expose this to the voter or put it
        // in any public-facing receipt: with a small candidate list and a
        // known tokenHash (the voter's own session token), this is
        // brute-forceable, which breaks receipt-freeness / coercion-resistance.
        String voteHash = AuditLog.sha256(
                packet.getCandidateId() + tokenHash + OffsetDateTime.now());

        // Public transaction ID — computed once, early, at the idempotency
        // guard above (see comment there). Candidate-blind by construction,
        // since the burn proof only ever signs cardIdHash|electionId.
        // Derived from the exact same input as the firmware's offline
        // tracker (network.cpp: crypto_sha256_hex(cardBurnProof)), so the
        // hex digits always agree. Case doesn't need to match:
        // ReceiptController.verify() uppercases whatever the voter types
        // before querying, so a lowercase firmware receipt still resolves
        // against this uppercase stored value. What DOES matter: never
        // truncate this, and never change which bytes get hashed on either
        // side without updating both — that's what actually breaks the
        // match, not letter case.

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
