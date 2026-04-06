package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class VoteReceiptDTO {
    private String transactionId;   // 16-char receipt shown on LCD
    private String encryptedAck;    // AES-encrypted ACK for terminal
    private String message;
}
