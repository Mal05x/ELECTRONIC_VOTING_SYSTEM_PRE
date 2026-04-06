package com.evoting.dto;
import lombok.Data;

@Data
public class SignStateChangeDTO {
    // Optional during initiation — the initiator signs AFTER the changeId is returned.
    // Required (validated in service) during co-signing.
    private String signature;
}
