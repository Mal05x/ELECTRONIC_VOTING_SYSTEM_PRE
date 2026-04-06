package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "candidates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Candidate {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "election_id",  nullable = false)  private UUID   electionId;
    @Column(name = "party_id")                        private UUID   partyId;      // FK to parties
    @Column(name = "full_name",    nullable = false)  private String fullName;
    private String party;      // kept for backwards compat / standalone use without Party table
    private String position;
    /** S3 object key for candidate photo */
    @Column(name = "image_s3_key") private String imageS3Key;
    /** Public/presigned URL served to terminal and dashboard */
    @Column(name = "image_url", columnDefinition = "TEXT") private String imageUrl;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();
}
