package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

/**
 * Returned by GET /api/terminal/pending_enrollment.
 * Contains all data the S3 firmware needs to personalize a JCOP 4 card.
 */
@Data @AllArgsConstructor
public class TerminalEnrollmentRecordDTO {
    private UUID   enrollmentId;
    private UUID   electionId;
    private Long   pollingUnitId;
    private String voterPublicKey;
    private String encryptedDemographic;
    /** Raw 16-byte per-card SCP03 static key (Base64). First field in personalize APDU. */
    private String cardStaticKey;
}
