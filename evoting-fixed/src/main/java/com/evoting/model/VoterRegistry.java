package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Voter identity record.
 *
 * voting_id:          INEC-style human-readable identifier: {StateCode}/{LGA2d}/{PU3d}/{Seq4d}
 *                     e.g. "KD/01/003/0042" — assigned at registration, printed on voter card.
 *
 * card_locked:        TRUE after the voter casts their ballot.
 *                     Reset to FALSE when SUPER_ADMIN unlocks for a new election.
 *
 * card_static_key_hash: SHA-256 of the per-card SCP03 static key written during personalization.
 *                       Raw key is never stored — only this hash for audit (Fix B-05).
 *
 * Biometrics NEVER stored here — they live only on the JCOP4 Smart Card (MoC design).
 */
@Entity @Table(name = "voter_registry")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VoterRegistry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;

    @Column(name = "election_id")                     private UUID    electionId;  // nullable — permanent identity records have no election_id
    @Column(name = "voting_id",     nullable = false, unique = true)  private String votingId;
    @Column(name = "card_id_hash",  nullable = false, unique = true)  private String cardIdHash;
    @Column(name = "voter_public_key", nullable = false, columnDefinition = "TEXT") private String voterPublicKey;
    @Column(name = "encrypted_demographic", columnDefinition = "TEXT") private String encryptedDemographic;

    /** SHA-256 of per-card SCP03 static key (Fix B-05). Raw key never persisted. */
    @Column(name = "card_static_key_hash", length = 64) private String cardStaticKeyHash;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "polling_unit_id", nullable = false)
    private PollingUnit pollingUnit;

    @Column(name = "has_voted",   nullable = false) private boolean hasVoted   = false;
    @Column(name = "card_locked", nullable = false) private boolean cardLocked = false;
    @Column(name = "registration_at") private OffsetDateTime registrationAt = OffsetDateTime.now();

    // V8: permanent enrollment fields
    @Column(name = "enrolled",             nullable = false) private boolean enrolled           = false;
    @Column(name = "enrolled_at")                            private OffsetDateTime enrolledAt;
    @Column(name = "enrolled_terminal_id")                   private String  enrolledTerminalId;
    @Column(name = "first_name",           length = 100)     private String  firstName;   // searchable plaintext
    @Column(name = "surname",              length = 100)     private String  surname;     // searchable plaintext

    public Lga   getLga()   { return pollingUnit != null ? pollingUnit.getLga()  : null; }
    public State getState() { return getLga() != null ? getLga().getState()      : null; }
}
