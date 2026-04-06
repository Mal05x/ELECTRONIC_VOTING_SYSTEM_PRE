package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "action_challenges")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ActionChallenge {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "admin_id",    nullable = false) private UUID   adminId;
    @Column(name = "nonce",       nullable = false, unique = true, length = 64)
    private String nonce;
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Builder.Default
    @Column(name = "created_at")  private OffsetDateTime createdAt = OffsetDateTime.now();
    @Builder.Default
    @Column(name = "expires_at")  private OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(60);
    @Builder.Default
    @Column(name = "used")        private boolean used = false;
    @Column(name = "used_at")     private OffsetDateTime usedAt;
}
