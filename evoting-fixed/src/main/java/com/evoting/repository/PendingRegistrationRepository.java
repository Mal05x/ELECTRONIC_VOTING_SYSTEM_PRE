package com.evoting.repository;
import com.evoting.model.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {
    Optional<PendingRegistration> findByCardIdHash(String cardIdHash);
    List<PendingRegistration> findByTerminalIdAndStatus(String terminalId, String status);
    List<PendingRegistration> findByStatus(String status);

    @Modifying
    @Query("UPDATE PendingRegistration p SET p.status = 'EXPIRED' " +
            "WHERE p.status = 'AWAITING_DEMOGRAPHICS' AND p.expiresAt < :now")
    int expireStale(@Param("now") OffsetDateTime now);
}
