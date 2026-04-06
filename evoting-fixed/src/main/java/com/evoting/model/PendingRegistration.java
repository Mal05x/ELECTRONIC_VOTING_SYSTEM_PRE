package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "pending_registrations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PendingRegistration {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "terminal_id",     nullable = false)  private String terminalId;
    @Column(name = "polling_unit_id", nullable = false)  private Long   pollingUnitId;
    @Column(name = "card_id_hash",    nullable = false, unique = true) private String cardIdHash;
    @Column(name = "voter_public_key",nullable = false, columnDefinition = "TEXT") private String voterPublicKey;
    @Builder.Default
    @Column(name = "status",          nullable = false)  private String status = "AWAITING_DEMOGRAPHICS";
    @Builder.Default
    @Column(name = "initiated_at")    private OffsetDateTime initiatedAt  = OffsetDateTime.now();
    @Builder.Default
    @Column(name = "expires_at")      private OffsetDateTime expiresAt    = OffsetDateTime.now().plusHours(4);
    @Column(name = "committed_by")    private String committedBy;
    @Column(name = "committed_at")    private OffsetDateTime committedAt;
    @Column(name = "voter_registry_id") private UUID voterRegistryId;
}
