package com.evoting.controller;
import com.evoting.model.Candidate;
import com.evoting.model.Party;
import com.evoting.repository.CandidateRepository;
import com.evoting.repository.PartyRepository;
import com.evoting.service.AuditLogService;
import com.evoting.service.S3Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
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
