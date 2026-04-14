package com.evoting.controller;
import com.evoting.model.Candidate;
import com.evoting.model.Party;
import com.evoting.repository.CandidateRepository;
import com.evoting.repository.PartyRepository;
import com.evoting.service.AuditLogService;
import com.evoting.service.S3Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkException;
import java.util.Map;
import java.util.UUID;

/**
 * Handles photo uploads for candidates and parties.
 * Images are stored on AWS S3; S3 key + presigned URL saved in DB.
 *
 * Endpoints (JWT required — ADMIN or SUPER_ADMIN):
 *   POST /api/admin/images/candidate/{candidateId}
 *   POST /api/admin/images/party/{partyId}
 *   DELETE /api/admin/images/candidate/{candidateId}
 */
@RestController
@RequestMapping("/api/admin/images")
@Slf4j
public class ImageController {

    @Autowired private CandidateRepository candidateRepo;
    @Autowired private PartyRepository     partyRepo;
    @Autowired private S3Service           s3;
    @Autowired private AuditLogService     auditLog;

    /**
     * POST /api/admin/images/candidate/{candidateId}
     * Multipart field name: "image"
     * Accepted types: image/jpeg, image/png, image/webp
     */
    @PostMapping("/candidate/{candidateId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> uploadCandidatePhoto(
            @PathVariable UUID candidateId,
            @RequestParam("image") MultipartFile image,
            Authentication auth) throws Exception {

        validateImage(image);
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));

        try {
            // Delete old photo from S3 if it exists
            if (candidate.getImageS3Key() != null) s3.delete(candidate.getImageS3Key());

            String key = s3.upload("candidates", image);
            String url = s3.generatePresignedUrl(key);
            candidate.setImageS3Key(key);
            candidate.setImageUrl(url);
            candidateRepo.save(candidate);

            auditLog.log("CANDIDATE_PHOTO_UPLOADED", auth.getName(),
                    "Candidate: " + candidate.getFullName() + " | Key: " + key);

            return ResponseEntity.ok(Map.of(
                    "candidateId", candidateId.toString(),
                    "imageUrl",    url,
                    "s3Key",       key));

        } catch (IllegalStateException e) {
            // S3 not configured — return 503 with actionable message
            log.warn("[PHOTO] S3 not configured: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", e.getMessage(),
                            "hint",  "Set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, " +
                                    "AWS_S3_BUCKET_NAME and AWS_S3_ENDPOINT on Render."
                    ));

        } catch (S3Exception e) {
            // Credentials wrong, bucket missing, wrong endpoint, permission denied, etc.
            log.error("[PHOTO] S3 error {}: {} — requestId={}", e.statusCode(), e.getMessage(),
                    e.requestId());
            String hint = switch (e.statusCode()) {
                case 403 -> "Check AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY — credentials " +
                        "rejected by the storage provider.";
                case 404 -> "Bucket not found. Check AWS_S3_BUCKET name matches exactly what " +
                        "is in Supabase Storage.";
                case 301, 307 -> "Wrong region or endpoint. Verify AWS_S3_ENDPOINT and " +
                        "AWS_REGION for your Supabase project.";
                default   -> "Storage provider returned HTTP " + e.statusCode() +
                        ". Check all AWS_* env vars on Render.";
            };
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Storage error: " + e.awsErrorDetails().errorMessage(),
                            "hint",  hint));

        } catch (SdkException e) {
            // Network error, timeout, SSL issue reaching the storage endpoint
            log.error("[PHOTO] SDK/network error reaching storage endpoint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "Could not reach storage endpoint: " + e.getMessage(),
                            "hint",  "Verify AWS_S3_ENDPOINT is correct and reachable from Render. " +
                                    "For Supabase: https://<project-ref>.supabase.co/storage/v1/s3"
                    ));
        }
    }

    /**
     * POST /api/admin/images/party/{partyId}
     * Multipart field name: "image"
     */
    @PostMapping("/party/{partyId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> uploadPartyLogo(
            @PathVariable UUID partyId,
            @RequestParam("image") MultipartFile image,
            Authentication auth) throws Exception {

        validateImage(image);
        Party party = partyRepo.findById(partyId)
                .orElseThrow(() -> new IllegalArgumentException("Party not found"));

        if (party.getLogoS3Key() != null) s3.delete(party.getLogoS3Key());

        String key = s3.upload("parties", image);
        String url = s3.generatePresignedUrl(key);
        party.setLogoS3Key(key);
        party.setLogoUrl(url);
        partyRepo.save(party);

        auditLog.log("PARTY_LOGO_UPLOADED", auth.getName(),
                "Party: " + party.getAbbreviation() + " | Key: " + key);

        return ResponseEntity.ok(Map.of(
                "partyId",  partyId.toString(),
                "logoUrl",  url,
                "s3Key",    key));
    }

    /**
     * DELETE /api/admin/images/candidate/{candidateId}
     */
    @DeleteMapping("/candidate/{candidateId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> deleteCandidatePhoto(
            @PathVariable UUID candidateId, Authentication auth) {
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        if (candidate.getImageS3Key() != null) {
            s3.delete(candidate.getImageS3Key());
            candidate.setImageS3Key(null);
            candidate.setImageUrl(null);
            candidateRepo.save(candidate);
        }
        auditLog.log("CANDIDATE_PHOTO_DELETED", auth.getName(), "Candidate: " + candidateId);
        return ResponseEntity.ok(Map.of("message", "Photo deleted"));
    }

    /** Refresh presigned URL (call if URL expires) */
    @PostMapping("/candidate/{candidateId}/refresh-url")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> refreshCandidateUrl(
            @PathVariable UUID candidateId, Authentication auth) {
        Candidate c = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        if (c.getImageS3Key() == null)
            return ResponseEntity.ok(Map.of("message", "No image on record"));
        String url = s3.generatePresignedUrl(c.getImageS3Key());
        c.setImageUrl(url);
        candidateRepo.save(c);
        return ResponseEntity.ok(Map.of("imageUrl", url));
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Image file is required");
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/"))
            throw new IllegalArgumentException("File must be an image (jpeg/png/webp)");
        if (file.getSize() > 5_242_880)  // 5 MB
            throw new IllegalArgumentException("Image must be smaller than 5 MB");
    }
}
