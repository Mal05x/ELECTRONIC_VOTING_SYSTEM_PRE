package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Idempotency guard for vote submissions.
 *
 * When an ESP32 sends a vote its cellular connection may drop before it
 * receives the HTTP 200. It will then retry with the exact same AES-GCM
 * encrypted payload. Because GCM is deterministic for the same plaintext +
 * nonce combination, the SHA-256 of the ciphertext uniquely identifies this
 * specific send attempt.
 *
 * On first receipt:  store payload_hash + transaction_id, process normally.
 * On retry:          look up payload_hash → return stored transaction_id immediately,
 *                    skip all processing — no duplicate ballot written.
 *
 * TTL: rows older than 24 hours are deleted by a scheduled job.
 */
@Entity @Table(name = "idempotency_keys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IdempotencyKey {
    @Id
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;       // SHA-256 of the raw encrypted bytes

    @Column(name = "transaction_id", nullable = false, length = 16)
    private String transactionId;     // the receipt returned for this payload

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
