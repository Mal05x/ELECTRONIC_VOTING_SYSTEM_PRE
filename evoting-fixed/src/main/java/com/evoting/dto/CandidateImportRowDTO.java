package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.UUID;

/**
 * One row from a JSON / CSV / Excel bulk candidate import.
 *
 * Fix 8: @NotBlank and @Size on all string fields so that malformed
 * CSV rows are rejected by Bean Validation before reaching the service
 * layer, instead of triggering a NullPointerException or a DB constraint
 * violation with a confusing error message.
 *
 * Note: validation is triggered manually in ElectionDataImportService
 * via a javax.validation.Validator injection, because the rows come from
 * file parsing rather than a Spring MVC @RequestBody.
 */
@Data
public class CandidateImportRowDTO {

    @NotBlank(message = "fullName is required")
    @Size(max = 255, message = "fullName must be at most 255 characters")
    private String fullName;

    @Size(max = 20, message = "partyAbbreviation must be at most 20 characters")
    private String partyAbbreviation;   // optional — independent candidates allowed

    @NotBlank(message = "position is required")
    @Size(max = 100, message = "position must be at most 100 characters")
    private String position;

    @NotNull(message = "electionId is required")
    private UUID electionId;
}
