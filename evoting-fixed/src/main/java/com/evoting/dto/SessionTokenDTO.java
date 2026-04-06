package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class SessionTokenDTO {
    private String encryptedSessionToken;
    private String message;
}
