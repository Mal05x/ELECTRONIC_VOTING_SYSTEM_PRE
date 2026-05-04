package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pending card personalisation record.
 *
 * Flow:
 *  1. SUPER_ADMIN POSTs to /api/admin/enrollment/queue.
 *     Server generates:
 *       a) 16-byte per-card SCP03 static key → stored Base64, hash stored for audit.
 *       b) 32-byte admin token → SHA-256 stored here, RAW returned once to admin.
 *  2. Terminal GETs /api/terminal/pending_enrollment → receives cardStaticKey (raw)
 *     and adminTokenHash (SHA-256).
 *  3. Terminal writes JCOP4 card via INS_PERSONALIZE (596-byte APDU):
 *       [0..15]   cardStaticKey
 *       [16..19]  voter PIN
 *       [20..51]  voterID
 *       [52..563] fingerprintTemplate
 *       [564..595] adminTokenHash   ← SHA-256 stored on card EEPROM
 *  4. Terminal POSTs completion to /api/terminal/enrollment.
 *  5. Service marks COMPLETED, creates voter_registry row, zeroes raw cardStaticKey.
 *     adminTokenHash is kept for audit; it is already on the card.
 *
 * Card locking (future):
 *   SUPER_ADMIN supplies rawAdminToken to the terminal via a secure channel.
 *   Terminal AES-encrypts it under the session key and sends INS_LOCK_CARD.
 *   Applet decrypts, SHA-256s, compares against stored adminTokenHash.
 *   Match → card permanently locked.
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

    /**
     * Base64-encoded raw 16-byte per-card SCP03 static key.
     * Delivered to terminal on GET /pending_enrollment.
     * Zeroed in DB after terminal confirms card write (COMPLETED).
     */
    @Column(name = "card_static_key", nullable = false, columnDefinition = "TEXT")
    private String cardStaticKey;

    /** SHA-256 hex of cardStaticKey raw bytes — persisted to voter_registry on completion. */
    @Column(name = "card_static_key_hash", columnDefinition = "bpchar")
    private String cardStaticKeyHash;

    /**
     * SHA-256 hex of the 32-byte raw admin token.
     * Delivered to terminal on GET /pending_enrollment as Base64.
     * Terminal writes this into bytes [564..595] of the INS_PERSONALIZE APDU.
     * The raw admin token is NEVER stored in the database — only this hash.
     */
    @Column(name = "admin_token_hash", length = 64)
    private String adminTokenHash;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    /** Populated by terminal on completion */
    @Column(name = "card_id_hash") private String cardIdHash;
    @Column(name = "voting_id")    private String votingId;

    @Column(name = "completed_at") private OffsetDateTime completedAt;
}
