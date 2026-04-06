package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Political party. Party logo stored on S3; logo_url is a presigned/public URL.
 */
@Entity @Table(name = "parties")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Party {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false, unique = true) private String abbreviation;  // e.g. "APC", "PDP"
    @Column(name = "logo_s3_key") private String logoS3Key;                // S3 object key
    @Column(name = "logo_url", columnDefinition = "TEXT") private String logoUrl;  // presigned URL
    @Column(name = "founded_year") private Integer foundedYear;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();
}
