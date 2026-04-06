package com.evoting.repository;
import com.evoting.model.EnrollmentQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentQueueRepository extends JpaRepository<EnrollmentQueue, UUID> {

    Optional<EnrollmentQueue> findFirstByTerminalIdAndStatusOrderByCreatedAtAsc(
            String terminalId, String status);

    List<EnrollmentQueue> findByElectionIdAndStatus(UUID electionId, String status);

    /** Zero the raw key after card write is confirmed — raw key should not persist. */
    @Modifying
    @Query("UPDATE EnrollmentQueue q SET q.cardStaticKey = 'CLEARED', q.status = 'COMPLETED', " +
            "q.cardIdHash = :cardIdHash, q.votingId = :votingId, q.completedAt = CURRENT_TIMESTAMP " +
            "WHERE q.id = :id")
    void markCompleted(@Param("id")         UUID   id,
                       @Param("cardIdHash") String cardIdHash,
                       @Param("votingId")   String votingId);
}
