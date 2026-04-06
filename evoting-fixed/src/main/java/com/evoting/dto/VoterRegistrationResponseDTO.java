package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Returned after successful voter registration — includes the generated Voting ID */
@Data @AllArgsConstructor
public class VoterRegistrationResponseDTO {
    private String votingId;         // e.g. "KD/01/003/0042"
    private String pollingUnitName;
    private String lgaName;
    private String stateName;
    private String message;
}
