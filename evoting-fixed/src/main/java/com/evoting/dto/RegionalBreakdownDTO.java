package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;
import java.util.Map;
import java.util.UUID;

/**
 * Generic regional breakdown DTO — adapts to the election's scope:
 *
 *   PRESIDENTIAL              → regionType = STATE
 *   GUBERNATORIAL / SENATORIAL
 *     / STATE_ASSEMBLY        → regionType = LGA
 *   LOCAL_GOVERNMENT          → regionType = POLLING_UNIT
 *
 * The frontend reads regionType to label the chart and legend accordingly.
 */
@Data @AllArgsConstructor
@NoArgsConstructor
public class RegionalBreakdownDTO {

    /** Numeric ID of the region (stateId, lgaId, or pollingUnitId cast to long) */
    private long   regionId;

    /** Human-readable name: state name, LGA name, or polling unit name */
    private String regionName;

    /** Short code where available (state code, LGA code, PU INEC code) */
    private String regionCode;

    /**
     * One of: "STATE" | "LGA" | "POLLING_UNIT"
     * Tells the frontend which label to use in the chart header and legend.
     */
    private String regionType;

    private long   totalVotes;

    /**
     * Registered voters in this region for this election.
     * Populated for STATE level only (requires a more complex JOIN for LGA/PU).
     * 0 for LGA and POLLING_UNIT — frontend should hide turnout % if 0.
     */
    private long   registeredVoters;

    /** Turnout percentage. 0.0 if registeredVoters == 0. */
    private double turnoutPercent;

    /** Per-candidate vote breakdown: candidateId (UUID string) → vote count */
    private Map<String, Long> candidateTally;
}
