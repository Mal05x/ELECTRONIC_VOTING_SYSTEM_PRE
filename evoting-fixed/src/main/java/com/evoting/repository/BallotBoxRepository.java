package com.evoting.repository;
import com.evoting.model.BallotBox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BallotBoxRepository extends JpaRepository<BallotBox, UUID> {

    boolean existsBySessionTokenHash(String sessionTokenHash);

    Optional<BallotBox> findByTransactionId(String transactionId);

    List<BallotBox> findByElectionId(UUID electionId);

    /**
     * Returns all vote hashes for an election in insertion order.
     * Used by AdminController.publishMerkleRoot() to recompute the Merkle root.
     * @Param required — named parameter :electionId must be bound explicitly.
     */
    @Query("SELECT b.voteHash FROM BallotBox b " +
            "WHERE b.electionId = :electionId ORDER BY b.castAt ASC")
    List<String> findHashesByElectionId(@Param("electionId") UUID electionId);

    @Query("SELECT COUNT(b) FROM BallotBox b WHERE b.electionId = :electionId")
    long countByElectionId(@Param("electionId") UUID electionId);

    @Query("SELECT b.candidateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId GROUP BY b.candidateId")
    List<Object[]> countVotesByCandidate(@Param("electionId") UUID electionId);

    @Query("SELECT b.candidateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.stateId = :stateId " +
            "GROUP BY b.candidateId")
    List<Object[]> countVotesByCandidateAndState(@Param("electionId") UUID electionId,
                                                 @Param("stateId")    Integer stateId);

    @Query("SELECT b.candidateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.lgaId = :lgaId " +
            "GROUP BY b.candidateId")
    List<Object[]> countVotesByCandidateAndLga(@Param("electionId") UUID electionId,
                                               @Param("lgaId")      Integer lgaId);

    @Query("SELECT b.stateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId GROUP BY b.stateId")
    List<Object[]> countVotesByState(@Param("electionId") UUID electionId);

    @Query("SELECT b.lgaId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.stateId = :stateId " +
            "GROUP BY b.lgaId")
    List<Object[]> countVotesByLgaInState(@Param("electionId") UUID electionId,
                                          @Param("stateId")    Integer stateId);

    /**
     * Group all ballots for an election by LGA — used for GUBERNATORIAL / SENATORIAL /
     * STATE_ASSEMBLY elections where the breakdown level is LGA, not state.
     * Returns Object[]{lgaId (Integer), voteCount (Long)}.
     */
    @Query("SELECT b.lgaId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId " +
            "GROUP BY b.lgaId ORDER BY COUNT(b) DESC")
    List<Object[]> countVotesByLga(@Param("electionId") UUID electionId);

    /**
     * Per-candidate breakdown for a specific LGA — used alongside countVotesByLga
     * to build the candidateTally map in RegionalBreakdownDTO.
     */
    @Query("SELECT b.candidateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.lgaId = :lgaId " +
            "GROUP BY b.candidateId")
    List<Object[]> countVotesByCandidateForLga(@Param("electionId") UUID electionId,
                                               @Param("lgaId")      Integer lgaId);

    /**
     * Group all ballots for an election by polling unit — used for LOCAL_GOVERNMENT
     * elections where the finest granularity (ward / PU) is needed.
     * Returns Object[]{pollingUnitId (Long), voteCount (Long)}.
     */
    @Query("SELECT b.pollingUnitId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.pollingUnitId IS NOT NULL " +
            "GROUP BY b.pollingUnitId ORDER BY COUNT(b) DESC")
    List<Object[]> countVotesByPollingUnit(@Param("electionId") UUID electionId);

    /**
     * Per-candidate breakdown for a specific polling unit.
     */
    @Query("SELECT b.candidateId, COUNT(b) FROM BallotBox b " +
            "WHERE b.electionId = :electionId AND b.pollingUnitId = :puId " +
            "GROUP BY b.candidateId")
    List<Object[]> countVotesByCandidateForPollingUnit(@Param("electionId") UUID electionId,
                                                       @Param("puId")       Long puId);

    /** Used by receipt tracker — not tally-related but placed here for proximity */
   // @Query("SELECT b.voteHash FROM BallotBox b WHERE b.electionId = :electionId")
    //List<String> findHashesByElectionId(@Param("electionId") UUID electionId);

}
