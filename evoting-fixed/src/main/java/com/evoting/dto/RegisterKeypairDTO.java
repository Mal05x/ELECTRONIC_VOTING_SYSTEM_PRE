package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterKeypairDTO {
    @NotBlank private String publicKey;  // Base64 ECDSA P-256 SubjectPublicKeyInfo
}
