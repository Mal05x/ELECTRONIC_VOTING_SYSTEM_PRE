package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "state_change_signatures",
        uniqueConstraints = @UniqueConstraint(columnNames = {"change_id","admin_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StateChangeSignature {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "change_id", nullable = false) private UUID   changeId;
    @Column(name = "admin_id",  nullable = false) private UUID   adminId;
    @Column(name = "signature", nullable = false, columnDefinition = "TEXT") private String signature;
    @Builder.Default
    @Column(name = "signed_at") private OffsetDateTime signedAt = OffsetDateTime.now();
}
