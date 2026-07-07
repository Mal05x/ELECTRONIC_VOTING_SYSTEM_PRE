package com.evoting.repository;
import com.evoting.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    Optional<IdempotencyKey> findByPayloadHash(String payloadHash);

    // 🔑 See VoteProcessingService.processVote() — the payloadHash guard above
    // only catches a literal byte-for-byte repeat of the SAME encrypted blob.
    // The ESP32 firmware re-encrypts with a fresh random IV (and a fresh
    // session token) on every retry, so payloadHash is different every time
    // and that guard never fires for the offline-recovery retry path. This
    // lookup is keyed on the cardBurnProof-derived transactionId instead,
    // which is identical across every retry of the same physical vote
    // (the card can only be burned once).
    Optional<IdempotencyKey> findByTransactionId(String transactionId);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
