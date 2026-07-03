package com.evoting.service;
import com.evoting.exception.EvotingAuthException;
import com.evoting.model.CardStatusLog;
import com.evoting.model.CardStatusLog.CardEvent;
import com.evoting.model.VoterRegistry;
import com.evoting.repository.CardStatusLogRepository;
import com.evoting.repository.VoterRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Smart Card Lock / Unlock management.
 *
 * LOCK:   Happens automatically when a voter successfully casts their ballot.
 *         A locked card cannot authenticate for the same election again.
 *
 * UNLOCK (bulk): Triggered automatically when a SUPER_ADMIN closes an election.
 *                All cards registered for that election are unlocked, allowing
 *                them to be used in future elections.
 *
 * UNLOCK (single): A SUPER_ADMIN can manually unlock a specific card
 *                  (e.g. if a voter was incorrectly flagged, or for testing).
 *
 * Every lock/unlock event is written to card_status_log (immutable audit trail).
 */
@Service @Slf4j
public class CardManagementService {

    @Autowired private VoterRegistryRepository voterRepo;
    @Autowired private CardStatusLogRepository logRepo;
    @Autowired private AuditLogService         auditLog;

    /**
     * BULK UNLOCK — called automatically when election status → CLOSED.
     * Returns the number of cards unlocked.
     */
    @Transactional
    public int bulkUnlockForElection(UUID electionId, String triggeredBy) {
        int count = voterRepo.unlockAllCardsForElection(electionId);

        // Log each card's unlock in card_status_log
        voterRepo.findByElectionId(electionId).forEach(v ->
            logRepo.save(new CardStatusLog(v.getCardIdHash(), electionId,
                CardEvent.UNLOCKED, triggeredBy)));

        auditLog.log("CARDS_BULK_UNLOCKED", triggeredBy,
            "Election: " + electionId + " | Cards unlocked: " + count);
        log.info("Bulk unlocked {} cards for election {}", count, electionId);
        return count;
    }

    /**
     * SINGLE UNLOCK — manual admin override for a specific card.
     */
    @Transactional
    public void unlockCard(String cardIdHash, UUID electionId, String unlockedBy) {
        VoterRegistry voter = voterRepo
            .findByCardIdHashAndElectionId(cardIdHash, electionId)
            .orElseThrow(() -> new EvotingAuthException("Card not found for this election"));

        if (!voter.isCardLocked()) {
            throw new IllegalStateException("Card is already unlocked");
        }

        voterRepo.unlockCard(cardIdHash, electionId);
        logRepo.save(new CardStatusLog(cardIdHash, electionId, CardEvent.UNLOCKED, unlockedBy));
        auditLog.log("CARD_MANUALLY_UNLOCKED", unlockedBy, "CardHash: " + cardIdHash);
        log.info("Card {} manually unlocked by {}", cardIdHash, unlockedBy);
    }

    /**
     * SINGLE LOCK — manual admin override (e.g. lost/stolen card).
     */
    @Transactional
    public void lockCard(String cardIdHash, UUID electionId, String lockedBy) {
        VoterRegistry voter = voterRepo
            .findByCardIdHashAndElectionId(cardIdHash, electionId)
            .orElseThrow(() -> new EvotingAuthException("Card not found for this election"));

        if (voter.isCardLocked()) {
            throw new IllegalStateException("Card is already locked");
        }

        voter.setCardLocked(true);
        voterRepo.save(voter);
        logRepo.save(new CardStatusLog(cardIdHash, electionId, CardEvent.LOCKED, lockedBy));
        auditLog.log("CARD_MANUALLY_LOCKED", lockedBy, "CardHash: " + cardIdHash);
    }

    /**
     * BIOMETRIC TRIES RESET — records that a terminal cleared a card's
     * on-card fingerprint/liveness try-counter lockout (MFAVotingApplet
     * INS_ADMIN_RESET_BIOMETRIC_TRIES, 0x33).
     *
     * Deliberately does NOT look up / validate against VoterRegistry the
     * way unlockCard()/lockCard() do, and never throws. The actual
     * authorization for this action already happened locally on the
     * JavaCard (admin-token verification inside the applet, before the
     * terminal ever calls this) — this method's only job is to record
     * that it happened. It must never be able to fail in a way that
     * makes the terminal think the reset itself didn't work; the log
     * write is best-effort, after the fact.
     *
     * pinTries/fpTries/livenessTries: the OBJECTIVE lockout counters read
     * straight from the card via INS_GET_LOCKOUT_STATUS (0x34) BEFORE the
     * reset — null if that query failed/wasn't available. This is what
     * actually closes the "how do you know this wasn't misused" gap: the
     * reason recorded here is what the card itself reported, not
     * something an officer typed in. Deliberately NOT added as new
     * card_status_log columns (avoids a schema migration this close to
     * a deadline) — folded into AuditLogService's existing free-text
     * eventData instead, which is already hash-chained and dashboard-visible.
     */
    @Transactional
    public void resetBiometricTries(String cardIdHash, UUID electionId, String triggeredBy,
                                     Integer pinTries, Integer fpTries, Integer livenessTries,
                                     boolean biometricReset) {
        logRepo.save(new CardStatusLog(cardIdHash, electionId, CardEvent.BIOMETRIC_TRIES_RESET, triggeredBy));

        String reason = String.format(
            "CardHash: %s | Pre-reset status (from card): PIN=%s FP=%s Liveness=%s | biometricReset=%b",
            cardIdHash,
            pinTries      != null ? pinTries      : "unknown",
            fpTries       != null ? fpTries       : "unknown",
            livenessTries != null ? livenessTries : "unknown",
            biometricReset
        );
        auditLog.log("CARD_BIOMETRIC_TRIES_RESET", triggeredBy, reason);
        log.info("Card {} biometric tries reset (triggered by {}) — pin={} fp={} liveness={} biometricReset={}",
                 cardIdHash, triggeredBy, pinTries, fpTries, livenessTries, biometricReset);
    }

    /**
     * Get full status history for a card.
     */
    public List<CardStatusLog> getCardHistory(String cardIdHash) {
        return logRepo.findByCardIdHashOrderByCreatedAtDesc(cardIdHash);
    }

    /**
     * Get all card events for an election.
     */
    public List<CardStatusLog> getElectionCardEvents(UUID electionId) {
        return logRepo.findByElectionIdOrderByCreatedAtDesc(electionId);
    }
}
