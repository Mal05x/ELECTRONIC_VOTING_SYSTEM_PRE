package com.evoting.repository;
import com.evoting.model.LivenessResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface LivenessResultRepository extends JpaRepository<LivenessResult, Long> {

    Optional<LivenessResult> findBySessionId(String sessionId);

    /** Clean up liveness results older than the given cutoff (scheduled job) */
    @Modifying
    @Transactional
    @Query("DELETE FROM LivenessResult l WHERE l.evaluatedAt < :cutoff")
    int deleteByEvaluatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
