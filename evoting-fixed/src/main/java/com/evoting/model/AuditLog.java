package com.evoting.model;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/** Immutable hash-chained audit record. Written once at construction — no setters. */
@Entity @Table(name = "audit_log")
@Getter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sequence_number", nullable = false, unique = true) private Long sequenceNumber;
    @Column(name = "event_type", nullable = false)   private String eventType;
    @Column(name = "actor",      nullable = false)   private String actor;
    @Column(name = "payload_hash",  nullable = false) private String payloadHash;
    @Column(name = "previous_hash", nullable = false) private String previousHash;
    @Column(name = "entry_hash",    nullable = false) private String entryHash;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    public AuditLog(Long seq, String eventType, String actor, String eventData, String previousHash) {
        this.sequenceNumber = seq;
        this.eventType      = eventType;
        this.actor          = actor;
        this.payloadHash    = sha256(eventType + actor + eventData);
        this.previousHash   = previousHash;
        this.entryHash      = sha256(seq + this.payloadHash + previousHash);
        this.createdAt      = OffsetDateTime.now();
    }

    /** Hash a UTF-8 string. Used by audit log chaining and general string digests. */
    public static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    /**
     * FIX BUG 3: Hash a raw byte array directly.
     *
     * This overload is used exclusively by MerkleTreeService to hash the binary
     * concatenation of two child node byte arrays. It avoids the second-preimage
     * vulnerability present in sha256(hexLeft + hexRight):
     *
     *   sha256("abc" + "def") == sha256("ab" + "cdef")  ← AMBIGUOUS (hex string concat)
     *   sha256Bytes(bytes("abc") + bytes("def"))         ← UNAMBIGUOUS (binary concat)
     *
     * Because sha256 operates on the raw 32-byte binary values (not their hex
     * representations), every pair of children maps to a unique parent hash.
     */
    public static String sha256Bytes(byte[] input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    /**
     * Decode a lowercase hex string to its raw byte array.
     * Used by MerkleTreeService to convert stored hex vote hashes back to bytes
     * before binary concatenation.
     */
    public static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
