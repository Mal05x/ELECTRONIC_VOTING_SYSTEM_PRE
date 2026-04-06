package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TerminalPendingRegistrationDTO {
    @NotBlank private String terminalId;
    @NotNull  private Long   pollingUnitId;
    @NotBlank private String cardIdHash;
    @NotBlank private String voterPublicKey;
}
