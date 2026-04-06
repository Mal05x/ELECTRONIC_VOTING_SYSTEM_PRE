package com.evoting.model;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "admin_users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdminUser {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true) private String username;
    @Column(nullable = true) private String email;
    @Column(name = "display_name") private String displayName;

    /**
     * @JsonIgnore ensures the bcrypt hash is NEVER serialised to JSON,
     * even if this entity is accidentally returned directly from a controller.
     * It is still readable by Java code (e.g. AdminUserService.authenticate).
     */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false) private String passwordHash;

    @Column(nullable = false) @Enumerated(EnumType.STRING)
    private AdminRole role = AdminRole.OBSERVER;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "last_login") private OffsetDateTime lastLogin;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();

    public enum AdminRole { SUPER_ADMIN, ADMIN, OBSERVER }
}
