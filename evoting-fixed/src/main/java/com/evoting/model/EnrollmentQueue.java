package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pending card personalisation record.
 *
 * Flow:
 *  1. SUPER_ADMIN POSTs to /api/admin/enrollment/queue — server generates
 *     a random per-card 16-byte staticKey, stores it Base64-encoded here,
 *     stores SHA-256(staticKey) in card_static_key_hash.
 *  2. Terminal GETs /api/terminal/pending_enrollment — receives this record
 *     including the raw cardStaticKey to include in the personalize APDU.
 *  3. Terminal writes JCOP 4 card, POSTs result to /api/terminal/enrollment.
 *  4. Service marks status=COMPLETED, writes voter_registry row, zeroes
 *     card_static_key (raw key no longer needed).
 */
@Entity @Table(name = "enrollment_queue")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EnrollmentQueue {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "terminal_id",           nullable = false) private String  terminalId;
    @Column(name = "election_id",           nullable = false) private UUID    electionId;
    @Column(name = "polling_unit_id",       nullable = false) private Long    pollingUnitId;
    @Column(name = "voter_public_key",      nullable = false, columnDefinition = "TEXT") private String voterPublicKey;
    @Column(name = "encrypted_demographic", columnDefinition = "TEXT") private String encryptedDemographic;

    /** Base64-encoded 16-byte per-card key for personalize APDU.  Zeroed after COMPLETED. */
    /** Base64-encoded 16-byte per-card key for personalize APDU. Zeroed after COMPLETED. */
    @Column(name = "card_static_key", nullable = false, columnDefinition = "TEXT")
    private String cardStaticKey;

    /** SHA-256 of cardStaticKey — copied to voter_registry.card_static_key_hash */
    @Column(name = "card_static_key_hash", columnDefinition = "bpchar")
    private String cardStaticKeyHash;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = "PENDING";
    /** SHA-256 of cardStaticKey — copied to voter_registry.card_static_key_hash */

    /** Populated by terminal on completion */
    @Column(name = "card_id_hash") private String cardIdHash;
    @Column(name = "voting_id")    private String votingId;

    @Column(name = "completed_at") private OffsetDateTime completedAt;
}
