package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "admin_keypairs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AdminKeypair {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "admin_id", nullable = false, unique = true)
    private UUID adminId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Builder.Default
    @Column(name = "algorithm", nullable = false)
    private String algorithm = "ECDSA-P256";

    @Builder.Default
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
}