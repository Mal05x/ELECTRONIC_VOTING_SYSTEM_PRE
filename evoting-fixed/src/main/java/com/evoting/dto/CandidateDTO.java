package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

@Data
public class CandidateDTO {
    @NotBlank private String fullName;
    private String           partyAbbreviation;  // e.g. "APC" — looked up in parties table
    @NotBlank private String position;
    @NotNull  private UUID   electionId;
}
