package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

/** Posted by terminal to /api/terminal/enrollment after card write is confirmed. */
@Data
public class EnrollmentResultDTO {
    @NotNull  private UUID   enrollmentId;
    @NotBlank private String cardIdHash;   // SHA-256 of JCOP 4 UID
    @NotBlank private String terminalId;
}
