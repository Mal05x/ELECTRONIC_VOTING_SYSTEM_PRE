package com.evoting.repository;
import com.evoting.model.VoterElectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoterElectionStatusRepository extends JpaRepository<VoterElectionStatus, UUID> {

    Optional<VoterElectionStatus> findByElectionIdAndCardIdHash(UUID electionId, String cardIdHash);

    List<VoterElectionStatus> findByElectionId(UUID electionId);

    /**
     * Atomic "cast vote" upsert. Mirrors the old markAsVotedAndLockByCardHash
     * guard (UPDATE ... WHERE has_voted = false) but scoped per election
     * instead of per card for life — see V29 migration for why that matters.
     * Returns 1 if this call won the race and recorded the vote, 0 if the
     * card had already voted in THIS election (concurrent double-submit, or
     * a genuine repeat attempt).
     */
    @Modifying
    @Query(value =
        "INSERT INTO voter_election_status (election_id, card_id_hash, has_voted, card_locked, voted_at) " +
        "VALUES (:electionId, :cardIdHash, true, true, now()) " +
        "ON CONFLICT (election_id, card_id_hash) " +
        "DO UPDATE SET has_voted = true, card_locked = true, voted_at = now() " +
        "WHERE voter_election_status.has_voted = false",
        nativeQuery = true)
    int markAsVotedAndLock(@Param("electionId") UUID electionId, @Param("cardIdHash") String cardIdHash);

    /** Bulk unlock — called automatically when an election closes. */
    @Modifying
    @Query("UPDATE VoterElectionStatus s SET s.cardLocked = false WHERE s.electionId = :electionId")
    int unlockAllForElection(@Param("electionId") UUID electionId);

    /** Manual single-card unlock. Row must already exist — service layer checks isCardLocked() first. */
    @Modifying
    @Query("UPDATE VoterElectionStatus s SET s.cardLocked = false " +
           "WHERE s.electionId = :electionId AND s.cardIdHash = :cardIdHash")
    int unlockCard(@Param("electionId") UUID electionId, @Param("cardIdHash") String cardIdHash);

    /**
     * Manual single-card lock (e.g. lost/stolen card). Upsert, since an
     * admin may lock a card pre-emptively before it has voted in this
     * election — when no row exists yet.
     */
    @Modifying
    @Query(value =
        "INSERT INTO voter_election_status (election_id, card_id_hash, has_voted, card_locked) " +
        "VALUES (:electionId, :cardIdHash, false, true) " +
        "ON CONFLICT (election_id, card_id_hash) DO UPDATE SET card_locked = true",
        nativeQuery = true)
    int lockCard(@Param("electionId") UUID electionId, @Param("cardIdHash") String cardIdHash);
}
