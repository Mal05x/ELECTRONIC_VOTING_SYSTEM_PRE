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

    /**
     * Nullable. Only meaningful when type != PRESIDENTIAL. When set, only
     * voters whose registered state (VoterRegistry.getState(), derived from
     * their enrolled polling unit) matches this value may vote in this
     * election — enforced in TerminalController.handleTerminalTap(). Left
     * null for PRESIDENTIAL elections (nationwide, no scoping needed) and
     * for any sub-national election an admin hasn't opted into scoping yet
     * — unset means "not enforced", not "no voters eligible".
     */
    @Column(name = "target_state_id")
    private Integer targetStateId;

    /**
     * Nullable. ONLY checked when type == LOCAL_GOVERNMENT — see
     * TerminalController.handleTerminalTap(). A LOCAL_GOVERNMENT election is
     * genuinely scoped to one LGA, so this is a clean 1:1 constraint the
     * same way targetStateId is for GUBERNATORIAL.
     *
     * Deliberately NOT enforced for SENATORIAL / STATE_ASSEMBLY even if an
     * admin sets it here: those constituencies can legitimately span
     * multiple LGAs, and this schema has no "set of LGAs per district"
     * concept. Enforcing a single LGA for those types would silently
     * reject real eligible voters from the district's other LGAs — worse
     * than just leaving them at state-level scoping. If you need real
     * senatorial/assembly constituency enforcement later, that needs a
     * proper Constituency entity mapping to a set of LGAs, not this field.
     */
    @Column(name = "target_lga_id")
    private Integer targetLgaId;

    public enum ElectionStatus { PENDING, ACTIVE, CLOSED }
}
