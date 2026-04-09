package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * TerminalRegistry — registered voting terminals with their ECDSA public keys.
 *
 * Each terminal generates a P-256 keypair on first boot (stored in ESP32 NVS).
 * The admin registers the terminal's public key via the admin dashboard.
 * Every subsequent request from that terminal must carry a valid ECDSA signature.
 *
 * This replaces mTLS terminal identity for cloud deployments (Render, Railway, etc.)
 * where the TLS edge proxy terminates before the application layer.
 */
@Entity @Table(name = "terminal_registry")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TerminalRegistry {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique terminal identifier — matches TERMINAL_ID constant in firmware. */
    @Column(name = "terminal_id", nullable = false, unique = true)
    private String terminalId;

    /**
     * ECDSA P-256 public key (Base64-encoded, SubjectPublicKeyInfo / X.509 format).
     * Generated on-device by mbedtls on first boot.
     * Registered by an admin via POST /api/admin/terminals/provision.
     */
    @Column(name = "public_key", nullable = false, length = 256)
    private String publicKey;

    /** Human-readable location or description (e.g. "Kaduna North Ward 3, Unit 7"). */
    @Column(name = "label")
    private String label;

    @Column(name = "polling_unit_id")
    private Integer pollingUnitId;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "registered_at", nullable = false)
    @Builder.Default
    private OffsetDateTime registeredAt = OffsetDateTime.now();

    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;
}
