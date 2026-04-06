package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

@Data
public class VoterRegistrationDTO {
    @NotBlank private String  cardIdHash;
    @NotBlank private String  voterPublicKey;       // EC public key (Base64, X509-encoded)
    @NotBlank private String  encryptedDemographic; // AES-256 encrypted: {surname,firstName,dob,gender}
    @NotNull  private UUID    electionId;
    @NotNull  private Long    pollingUnitId;         // resolves full State->LGA->PollingUnit chain
}
