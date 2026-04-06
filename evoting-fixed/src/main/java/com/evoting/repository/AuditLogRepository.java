package com.evoting.repository;
import com.evoting.model.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Fix 1 — Row-level advisory lock for hash-chain serialisation.
     *
     * BUG: The previous version selected only the scalar `a.entryHash` projection.
     * Spring Data JPA's @Lock annotation works by adding a LockModeType to the
     * underlying Hibernate query. For scalar projections (non-entity results),
     * Hibernate silently ignores the lock mode — no FOR UPDATE is emitted.
     * Concurrent chain appends can therefore still read a stale previous_hash,
     * producing a sequence collision or a broken chain.
     *
     * FIX: Select the full entity. Hibernate then emits:
     *   SELECT * FROM audit_log WHERE ... FOR UPDATE
     * Only one transaction can hold this row lock at a time, serialising every
     * call to AuditLogService.log() at the database level — safe in multi-instance
     * / Kubernetes deployments where a JVM-level synchronized block would not help.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.sequenceNumber = " +
           "(SELECT MAX(b.sequenceNumber) FROM AuditLog b)")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditLog> findLatestForUpdate();

    @Query("SELECT COALESCE(MAX(a.sequenceNumber), 0) FROM AuditLog a")
    Long findMaxSequenceNumber();

    List<AuditLog> findAllByOrderBySequenceNumberAsc();

    /** Fix 10: efficient paginated audit log — delegates LIMIT/OFFSET to PostgreSQL. */
    Page<AuditLog> findAll(Pageable pageable);
}
