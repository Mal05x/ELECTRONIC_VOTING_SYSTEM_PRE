package com.evoting.repository;
import com.evoting.model.VoterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoterRegistryRepository extends JpaRepository<VoterRegistry, UUID> {

    Optional<VoterRegistry> findByCardIdHashAndElectionId(String cardIdHash, UUID electionId);
    Optional<VoterRegistry> findByCardIdHash(String cardIdHash);  // V8: permanent lookup without election scope

    Optional<VoterRegistry> findByVotingId(String votingId);

    @Modifying
    @Query("UPDATE VoterRegistry v SET v.hasVoted = true, v.cardLocked = true " +
            "WHERE v.cardIdHash = :cardIdHash AND v.electionId = :electionId")
    int markAsVotedAndLock(@Param("cardIdHash") String cardIdHash,
                           @Param("electionId") UUID electionId);

    @Modifying
    @Query("UPDATE VoterRegistry v SET v.cardLocked = false WHERE v.electionId = :electionId")
    int unlockAllCardsForElection(@Param("electionId") UUID electionId);

    @Modifying
    @Query("UPDATE VoterRegistry v SET v.cardLocked = false " +
            "WHERE v.cardIdHash = :cardIdHash AND v.electionId = :electionId")
    int unlockCard(@Param("cardIdHash") String cardIdHash,
                   @Param("electionId") UUID electionId);

    @Query("SELECT COUNT(v) FROM VoterRegistry v WHERE v.pollingUnit.id = :pollingUnitId")
    long countByPollingUnitId(@Param("pollingUnitId") Long pollingUnitId);

    /** Fix 10: paginated list for admin voter management */
    @Query("SELECT v FROM VoterRegistry v WHERE v.electionId = :electionId")
    Page<VoterRegistry> findByElectionIdPageable(@Param("electionId") UUID electionId,
                                                 Pageable pageable);

    List<VoterRegistry> findByElectionId(UUID electionId);

    long countByHasVotedTrue();
    long countByElectionIdAndPollingUnitLgaStateId(UUID electionId, Integer stateId);
    long countByElectionIdAndHasVotedTrueAndPollingUnitLgaStateId(UUID electionId, Integer stateId);
}
