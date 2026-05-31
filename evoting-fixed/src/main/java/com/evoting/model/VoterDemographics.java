package com.evoting.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "voter_demographics")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VoterDemographics {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "voter_id", nullable = false, unique = true)
    private UUID voterId;

    // pgp_sym_encrypt output — contains embedded IV
    @Column(name = "encrypted_data", nullable = false, columnDefinition = "TEXT")
    private String encryptedData;

    // FIX: Added @Builder.Default so Lombok stops erasing the timestamp!
    @Builder.Default
    @Column(name = "created_at") 
    private OffsetDateTime createdAt = OffsetDateTime.now();
    
    @Column(name = "last_accessed_by") private String lastAccessedBy;
    @Column(name = "last_accessed_at") private OffsetDateTime lastAccessedAt;
    
    @Builder.Default
    @Column(name = "access_count")     private int accessCount = 0;

    // BULLETPROOFING: Guarantees a timestamp exists before saving to PostgreSQL
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
    }
}
