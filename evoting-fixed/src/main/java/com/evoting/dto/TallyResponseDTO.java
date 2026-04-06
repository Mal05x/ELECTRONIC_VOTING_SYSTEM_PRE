package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data @AllArgsConstructor
public class TallyResponseDTO {
    private UUID                    electionId;
    private Map<String, Long>       candidateVotes;
    private String                  merkleRoot;
    private long                    totalVotes;
    private List<StateBreakdownDTO> stateBreakdown; // null unless /by-state requested
}
