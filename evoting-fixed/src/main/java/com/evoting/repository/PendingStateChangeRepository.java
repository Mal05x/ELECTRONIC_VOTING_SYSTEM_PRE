package com.evoting.repository;
import com.evoting.model.PendingStateChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
public interface PendingStateChangeRepository extends JpaRepository<PendingStateChange, UUID> {

    @Query("SELECT p FROM PendingStateChange p WHERE p.executed = false " +
            "AND p.cancelled = false AND p.expiresAt > :now ORDER BY p.createdAt DESC")
    List<PendingStateChange> findActivePending(@Param("now") OffsetDateTime now);

    @Query("SELECT p FROM PendingStateChange p WHERE p.executed = false " +
            "AND p.cancelled = false AND p.expiresAt <= :now")
    List<PendingStateChange> findExpired(@Param("now") OffsetDateTime now);
}
