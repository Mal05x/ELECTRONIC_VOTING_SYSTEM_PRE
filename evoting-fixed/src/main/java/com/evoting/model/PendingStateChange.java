package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "pending_state_changes")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PendingStateChange {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_type",   nullable = false) private String actionType;
    @Column(name = "target_id",     nullable = false) private String targetId;
    @Column(name = "target_label")                    private String targetLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "initiated_by",        nullable = false) private UUID    initiatedBy;
    @Builder.Default
    @Column(name = "signatures_required", nullable = false) private int     signaturesRequired = 2;
    @Builder.Default
    @Column(name = "created_at")  private OffsetDateTime createdAt  = OffsetDateTime.now();
    @Builder.Default
    @Column(name = "expires_at")  private OffsetDateTime expiresAt  = OffsetDateTime.now().plusHours(24);
    @Builder.Default
    @Column(name = "executed")    private boolean executed  = false;
    @Column(name = "executed_at") private OffsetDateTime executedAt;
    @Builder.Default
    @Column(name = "cancelled")   private boolean cancelled = false;
    @Column(name = "cancelled_by")private UUID    cancelledBy;
    @Column(name = "cancelled_at")private OffsetDateTime cancelledAt;
    @Column(name = "cancel_reason") private String cancelReason;
}
