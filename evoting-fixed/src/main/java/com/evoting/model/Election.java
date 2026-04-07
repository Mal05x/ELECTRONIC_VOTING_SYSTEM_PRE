package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "elections")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Election {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ElectionStatus status = ElectionStatus.PENDING;

    /**
     * Electoral scope — maps to the type column added in V11 migration.
     * Default: PRESIDENTIAL (matches DB DEFAULT 'PRESIDENTIAL').
     * Stored as VARCHAR; validated in DB via chk_election_type constraint.
     */
    @Column(nullable = false)
    @Builder.Default
    private String type = "PRESIDENTIAL";

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // ── MULTI-SIG VAULT ADDITIONS ──────────────────────────────────────────

    @Column(name = "required_signatures", nullable = false)
    @Builder.Default
    private Integer requiredSignatures = 3; // The rule: 3 admins must sign off

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "election_approvals", joinColumns = @JoinColumn(name = "election_id"))
    @Column(name = "admin_username")
    @Builder.Default
    private Set<String> approvedByAdmins = new HashSet<>();

    // ───────────────────────────────────────────────────────────────────────

    // Added PENDING_APPROVAL for the locked middle state
    public enum ElectionStatus { PENDING, PENDING_APPROVAL, ACTIVE, CLOSED }
}