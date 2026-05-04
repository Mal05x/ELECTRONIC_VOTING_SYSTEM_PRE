package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

/**
 * Returned by GET /api/terminal/pending_enrollment.
 * Contains all data the ESP32-S3 firmware needs to personalize a JCOP4 card.
 *
 * INS_PERSONALIZE APDU layout (596 bytes total):
 *   [0  .. 15 ] cardStaticKey     — 16-byte SCP03 key (Base64 decoded to raw bytes)
 *   [16 .. 19 ] voter PIN         — entered by voter on terminal keypad (4 bytes)
 *   [20 .. 51 ] voterID           — SHA-256(enrollmentId UTF-8) (32 bytes)
 *   [52 .. 563] fingerprintTemplate — R307 template captured at terminal (512 bytes)
 *   [564.. 595] adminTokenHash    — SHA-256 of admin token (Base64 decoded to 32 bytes)
 *
 * The voter PIN is NOT included here — it is chosen by the voter on the terminal
 * and never transmitted to or from the backend.
 *
 * adminTokenHash security note:
 *   The raw 32-byte admin token is generated server-side in EnrollmentService
 *   and returned ONCE to the SUPER_ADMIN on POST /api/admin/enrollment/queue.
 *   Only its SHA-256 is stored in the database and delivered here.
 *   The terminal writes SHA-256(adminToken) directly to the card.
 *   For future card decommissioning, the SUPER_ADMIN provides rawAdminToken
 *   to the terminal, which encrypts and sends it via INS_LOCK_CARD.
 *   The applet SHA-256s it and compares against the stored hash.
 */
@Data @AllArgsConstructor
public class TerminalEnrollmentRecordDTO {
    private UUID   enrollmentId;
    private UUID   electionId;
    private Long   pollingUnitId;
    private String voterPublicKey;
    private String encryptedDemographic;
    /** Base64 of raw 16-byte per-card SCP03 static key. */
    private String cardStaticKey;
    /**
     * Base64 of SHA-256(rawAdminToken) — 32 bytes.
     * Written to card bytes [564..595] in INS_PERSONALIZE.
     * Never the raw token itself.
     */
    private String adminTokenHash;
}
