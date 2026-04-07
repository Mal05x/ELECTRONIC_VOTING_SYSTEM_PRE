package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "elections")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Election {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ElectionStatus status = ElectionStatus.PENDING;

    /**
     * Electoral scope — maps to the type column added in V11 migration.
     * Default: PRESIDENTIAL (matches DB DEFAULT 'PRESIDENTIAL').
     * Stored as VARCHAR; validated in DB via chk_election_type constraint.
     */
    @Column(nullable = false)
    private String type = "PRESIDENTIAL";

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public enum ElectionStatus { PENDING, ACTIVE, CLOSED }
}
