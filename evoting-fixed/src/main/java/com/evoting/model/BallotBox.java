package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Anonymous vote record. NO FK to voter_registry — anonymity enforced at schema level.
 *
 * JPA indexes declared here keep them in sync with the entity definition and
 * generate the correct DDL when ddl-auto is used in tests. The same indexes
 * are also present in the V1 Flyway migration for production.
 *
 * Critical indexes for tally queries on large elections:
 *  - (election_id, candidate_id)           → national tally GROUP BY
 *  - (election_id, state_id, candidate_id) → state tally GROUP BY
 *  - (election_id, lga_id)                 → LGA drill-down
 *  - (session_token_hash)                  → replay prevention EXISTS check
 *  - (transaction_id)                      → public receipt lookup
 */
@Entity
@Table(
    name = "ballot_box",
    indexes = {
        @Index(name = "idx_bb_election_candidate",
               columnList = "election_id, candidate_id"),
        @Index(name = "idx_bb_election_state_candidate",
               columnList = "election_id, state_id, candidate_id"),
        @Index(name = "idx_bb_election_lga",
               columnList = "election_id, lga_id"),
        @Index(name = "idx_bb_election_pu",
               columnList = "election_id, polling_unit_id"),
        @Index(name = "idx_bb_session_token",
               columnList = "session_token_hash", unique = true),
        @Index(name = "idx_bb_transaction_id",
               columnList = "transaction_id", unique = true),
        @Index(name = "idx_bb_cast_at",
               columnList = "election_id, cast_at DESC")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BallotBox {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "election_id",  nullable = false) private UUID   electionId;
    @Column(name = "candidate_id", nullable = false) private UUID   candidateId;
    @Column(name = "vote_hash",    nullable = false, unique = true) private String voteHash;
    @Column(name = "transaction_id",     nullable = false, unique = true) private String transactionId;
    @Column(name = "session_token_hash", nullable = false, unique = true) private String sessionTokenHash;
    @Column(name = "terminal_id")     private String  terminalId;
    @Column(name = "state_id")        private Integer stateId;
    @Column(name = "lga_id")          private Integer lgaId;
    @Column(name = "polling_unit_id") private Long    pollingUnitId;
    @Column(name = "cast_at")         private OffsetDateTime castAt = OffsetDateTime.now();
}
