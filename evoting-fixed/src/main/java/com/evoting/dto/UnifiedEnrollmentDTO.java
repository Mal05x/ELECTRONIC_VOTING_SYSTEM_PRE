package com.evoting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UnifiedEnrollmentDTO {
    @NotBlank private String terminalId;
    @NotBlank private String cardIdHash;
    
    // Demographics
    @NotBlank private String firstName;
    @NotBlank private String surname;
    @NotBlank private String dateOfBirth;
    @NotBlank private String gender;
    
    // Location
    @NotNull  private Long pollingUnitId;
}
