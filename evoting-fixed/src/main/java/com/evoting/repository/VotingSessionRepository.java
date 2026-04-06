package com.evoting.repository;
import com.evoting.model.VotingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface VotingSessionRepository extends JpaRepository<VotingSession, UUID> {
    Optional<VotingSession> findBySessionTokenHashAndUsedFalse(String sessionTokenHash);

    /**
     * Fix B-13: Delete expired sessions for the nightly cleanup job.
     * Prevents unbounded voting_sessions table growth in long-running elections.
     */
    @Modifying
    @Query("DELETE FROM VotingSession s WHERE s.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
