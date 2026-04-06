package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InitiateStateChangeDTO {
    @NotBlank private String targetId;
    @NotBlank private String signature;  // ECDSA signature of a pre-challenge, or empty for initiation only
}
