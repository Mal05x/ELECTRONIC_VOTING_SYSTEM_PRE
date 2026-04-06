package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommitRegistrationDTO {
    @NotBlank @Size(max = 100)
    private String firstName;

    @NotBlank @Size(max = 100)
    private String surname;

    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "dob must be YYYY-MM-DD")
    private String dateOfBirth;

    @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER", message = "gender must be MALE, FEMALE, or OTHER")
    private String gender;
}
