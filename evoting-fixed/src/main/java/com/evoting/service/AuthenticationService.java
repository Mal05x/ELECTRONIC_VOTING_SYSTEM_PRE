package com.evoting.service;
import com.evoting.dto.*;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.*;
import com.evoting.repository.*;
import com.evoting.security.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service @Slf4j
public class AuthenticationService {

    /**
     * Canonical message the JCOP 4 card signs with its private key.
     * Both the card (applet) and the server must agree on this exact string.
     */
    public static final String SIGNED_MESSAGE = "Identity Cryptographically Verified";

    @Autowired private CryptoService           crypto;
    @Autowired private VoterRegistryRepository voterRepo;
    @Autowired private VotingSessionRepository sessionRepo;
    @Autowired private AuditLogService         auditLog;
    @Autowired private ObjectMapper            mapper;
    @Autowired private BiometricService        biometricService;  // V2 addition

    /**
     * Phase 3 authentication (V2 architecture):
     *
     *  1. Decrypt AES-256-GCM bundle.
     *  2. Liveness check — V2: query BiometricService using sessionId.
     *     Fallback: accept legacy livenessFlag=true from V1 firmware.
     *  3. Look up voter in registry.
     *  4. Check has_voted (double-vote prevention).
     *  5. Check card_locked.
     *  6. Validate signedMessage == canonical constant (Fix B-03).
     *  7. Verify JCOP4 ECDSA signature.
     *  8. Issue 5-minute session token.
     */
    @Transactional
    public SessionTokenDTO authenticate(String encryptedPayload) throws Exception {
        byte[]        raw    = crypto.decrypt(encryptedPayload);
        AuthPacketDTO packet = mapper.readValue(raw, AuthPacketDTO.class);
        String actor = packet.getTerminalId();

        // ── Step 2: Liveness ─────────────────────────────────────────────────
        //
        // V2 path: sessionId present → query BiometricService for server-evaluated result.
        // V1 path: no sessionId → fall back to local livenessFlag in packet (legacy).
        //
        boolean livenessOk = false;
        if (packet.getSessionId() != null && !packet.getSessionId().isBlank()) {
            // V2: server-side liveness — BiometricService resolved the CAM stream
            try {
                livenessOk = biometricService.getLivenessResult(packet.getSessionId());
            } catch (EvotingAuthException e) {
                auditLog.log("AUTH_FAIL_LIVENESS_NO_STREAM", actor,
                        "SessionID=" + packet.getSessionId()
                                + " Card=" + packet.getCardIdHash());
                throw e;  // propagate: "Liveness result not found / expired"
            }
        } else {
            // V1 legacy fallback: use livenessFlag from the auth packet
            livenessOk = Boolean.TRUE.equals(packet.getLivenessFlag());
            if (livenessOk) {
                log.info("V1 liveness fallback accepted for terminal {}", actor);
            }
        }

        if (!livenessOk) {
            auditLog.log("AUTH_FAIL_LIVENESS", actor,
                    "Card=" + packet.getCardIdHash());
            throw new EvotingAuthException("Liveness detection failed");
        }

        // ── Step 3: Voter lookup ─────────────────────────────────────────────
        VoterRegistry voter = voterRepo
                .findByCardIdHashAndElectionId(packet.getCardIdHash(), packet.getElectionId())
                .orElseThrow(() -> {
                    auditLog.log("AUTH_FAIL_NOT_REGISTERED", actor, packet.getCardIdHash());
                    return new EvotingAuthException("Voter not registered for this election");
                });

        // ── Step 4: Double-vote check ─────────────────────────────────────────
        if (voter.isHasVoted()) {
            auditLog.log("AUTH_FAIL_ALREADY_VOTED", actor, voter.getVotingId());
            throw new EvotingAuthException("Voter has already cast a ballot");
        }

        // ── Step 5: Card lock check ───────────────────────────────────────────
        if (voter.isCardLocked()) {
            auditLog.log("AUTH_FAIL_CARD_LOCKED", actor, voter.getVotingId());
            throw new EvotingAuthException("Smart card is locked. Contact your presiding officer.");
        }

        // ── Step 6: Validate signedMessage (Fix B-03) ────────────────────────
        if (!SIGNED_MESSAGE.equals(packet.getSignedMessage())) {
            auditLog.log("AUTH_FAIL_MESSAGE_MISMATCH", actor, voter.getVotingId());
            throw new EvotingAuthException("Signed message does not match canonical assertion");
        }

        // ── Step 7: Verify ECDSA signature ───────────────────────────────────
        boolean valid = crypto.verifyCardSignature(
                voter.getVoterPublicKey(),
                packet.getSignedMessage(),
                packet.getCardSignature());
        if (!valid) {
            auditLog.log("AUTH_FAIL_SIGNATURE", actor, voter.getVotingId());
            throw new EvotingAuthException("Card cryptographic assertion invalid");
        }

        // ── Step 8: Issue 5-minute session token ─────────────────────────────
        PollingUnit pu    = voter.getPollingUnit();
        String token      = UUID.randomUUID().toString();
        String tokenHash  = AuditLog.sha256(token);

        sessionRepo.save(VotingSession.builder()
                .sessionTokenHash(tokenHash)
                .electionId(packet.getElectionId())
                .terminalId(actor)
                .pollingUnitId(pu != null ? pu.getId()                         : null)
                .stateId(voter.getState() != null ? voter.getState().getId()   : null)
                .lgaId(voter.getLga()     != null ? voter.getLga().getId()     : null)
                .expiresAt(OffsetDateTime.now().plusMinutes(5))
                .build());

        auditLog.log("AUTH_SUCCESS", actor,
                "VotingID=" + voter.getVotingId()
                        + " state=" + (voter.getState() != null ? voter.getState().getName() : "?")
                        + (packet.getSessionId() != null ? " V2liveness=OK" : " V1liveness=OK"));

        return new SessionTokenDTO(crypto.encrypt(token.getBytes()), "Authentication successful");
    }
}
