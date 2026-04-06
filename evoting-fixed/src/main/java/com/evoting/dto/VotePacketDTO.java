package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.util.UUID;

/**
 * Decrypted payload from the ESP32-S3 vote submission.
 *
 * Fix B-07: All fields now have bean validation annotations matching the
 * AuthPacketDTO pattern — prevents NullPointerException on malformed packets.
 *
 * Fix B-04: cardBurnProof field added — Base64-encoded ECDSA signature
 * produced by the JCOP 4 card when setVoted() (INS 0x51) is called.
 * The server verifies this against the voter's registered public key to
 * confirm the physical card was cryptographically burned.
 */
@Data
public class VotePacketDTO {

    @NotBlank
    private String sessionToken;

    /** UUID of the selected candidate */
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
             message = "candidateId must be a valid UUID")
    private String candidateId;

    @NotBlank
    private String cardIdHash;

    @NotBlank
    private String terminalId;

    @NotNull
    private UUID electionId;

    /**
     * Fix B-04: ECDSA signature from JCOP 4 applet INS_SET_VOTED (0x51).
     * The applet signs the VoterID (32-byte ASCII-hex) with its private key.
     * Server verifies this against voter_registry.voter_public_key.
     */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9+/=]+$",
             message = "cardBurnProof must be a valid Base64-encoded ECDSA signature")
    private String cardBurnProof;
}
