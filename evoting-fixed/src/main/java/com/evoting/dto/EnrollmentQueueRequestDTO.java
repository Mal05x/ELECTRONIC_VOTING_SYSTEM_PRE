package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

/** Admin queues a pending voter enrollment for a specific terminal. */
@Data
public class EnrollmentQueueRequestDTO {
    @NotBlank private String terminalId;
   /* @NotNull */ private UUID   electionId;
    @NotNull  private Long   pollingUnitId;
    @NotBlank private String voterPublicKey;       // EC public key (Base64, X509-encoded)
    private String           encryptedDemographic; // AES-256 encrypted demographics
}
