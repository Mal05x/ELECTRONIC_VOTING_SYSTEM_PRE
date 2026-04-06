package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 5-minute single-use session key. Geo IDs copied from the voter at auth time,
 * so VoteProcessingService can write them to ballot_box without an extra DB lookup.
 */
@Entity @Table(name = "voting_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VotingSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "session_token_hash", nullable = false, unique = true) private String sessionTokenHash;
    @Column(name = "election_id",  nullable = false) private UUID    electionId;
    @Column(name = "terminal_id",  nullable = false) private String  terminalId;
    @Column(name = "polling_unit_id") private Long    pollingUnitId;
    @Column(name = "state_id")        private Integer stateId;
    @Column(name = "lga_id")          private Integer lgaId;
    @Column(name = "is_used",   nullable = false) private boolean      used      = false;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();
}
