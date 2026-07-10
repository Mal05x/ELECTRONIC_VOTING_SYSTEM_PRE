package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-(election, card) voting status. Source of truth for vote-gating as of
 * V29 — see that migration for why voter_registry.hasVoted / cardLocked
 * (lifetime flags on a now-permanent identity row) can't be used for this.
 *
 * Keyed on (electionId, cardIdHash) rather than a VoterRegistry FK, mirroring
 * the existing CardStatusLog convention, so it works uniformly for both
 * legacy per-election voter_registry rows and post-V8 permanent ones.
 */
@Entity @Table(name = "voter_election_status")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VoterElectionStatus {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;

    @Column(name = "election_id",  nullable = false) private UUID electionId;
    @Column(name = "card_id_hash", nullable = false, length = 64) private String cardIdHash;

    @Column(name = "has_voted",   nullable = false) private boolean hasVoted   = false;
    @Column(name = "card_locked", nullable = false) private boolean cardLocked = false;

    @Column(name = "voted_at") private OffsetDateTime votedAt;
}
