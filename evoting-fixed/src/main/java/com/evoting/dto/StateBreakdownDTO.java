package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.Map;
import java.util.UUID;

@Data @AllArgsConstructor
public class StateBreakdownDTO {
    private UUID              electionId;
    private String            stateName;
    private String            stateCode;
    private Integer           stateId;
    private long              totalVotes;
    private long              registeredVoters;
    private long              votedVoters;
    private double            turnoutPercent;
    private Map<String, Long> candidateTally; // candidateId -> vote count
}
