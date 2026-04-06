package com.evoting.repository;
import com.evoting.model.PollingUnitVoterSeq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PollingUnitVoterSeqRepository extends JpaRepository<PollingUnitVoterSeq, Long> {

    /**
     * Atomically inserts or increments the counter for a polling unit.
     * Safe across multiple application instances because the DB executes this atomically.
     */
    @Modifying
    @Query(value =
            "INSERT INTO polling_unit_voter_seq (polling_unit_id, next_val) VALUES (:id, 1) " +
                    "ON CONFLICT (polling_unit_id) DO UPDATE " +
                    "SET next_val = polling_unit_voter_seq.next_val + 1 " +
                    "RETURNING next_val",
            nativeQuery = true)
    Long incrementAndGet(@Param("id") Long id);
}
