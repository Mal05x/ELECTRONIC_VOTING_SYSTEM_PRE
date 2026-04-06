package com.evoting.repository;

import com.evoting.model.ActionChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ActionChallengeRepository extends JpaRepository<ActionChallenge, UUID> {
    Optional<ActionChallenge> findByNonce(String nonce);

    @Modifying
    @Query("DELETE FROM ActionChallenge c WHERE c.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
