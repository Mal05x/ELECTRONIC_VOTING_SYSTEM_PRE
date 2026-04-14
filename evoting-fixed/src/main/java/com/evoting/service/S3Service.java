package com.evoting.service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * AWS S3 service for candidate photos and party logos.
 *
 * Fix B-11: S3Client and S3Presigner are now Spring-managed singletons,
 * created once via @PostConstruct and closed via @PreDestroy.
 *
 * BUG: The previous implementation called S3Client.builder().build() on every
 * upload/delete/presign call. AWS SDK v2 clients are heavyweight — they create
 * an internal HTTP client with thread pools and connection pools. Per-call
 * construction leaked OS TCP connections, threads, and file descriptors.
 *
 * FIX: Create both clients once, reuse across all calls. AWS documentation
 * explicitly states clients should be created once and reused.
 */
@Service @Slf4j
public class S3Service {

    @Value("${aws.s3.bucket}")                       private String bucket;
    @Value("${aws.s3.region}")                       private String region;
    @Value("${aws.access-key-id}")                   private String accessKeyId;
    @Value("${aws.secret-access-key}")               private String secretKey;
    @Value("${aws.s3.presigned-url-expiry-hours:168}") private int    expiryHours;

    /**
     * Optional S3-compatible endpoint override.
     * Required for Supabase Storage, MinIO, Cloudflare R2, DigitalOcean Spaces, etc.
     * Leave blank/unset for real AWS S3.
     *
     * Supabase endpoint format:
     *   https://<project-ref>.supabase.co/storage/v1/s3
     *
     * Set env var:  AWS_S3_ENDPOINT=https://<project-ref>.supabase.co/storage/v1/s3
     */
    @Value("${aws.s3.endpoint:}")
    private String endpointOverride;

    // Fix B-11: singleton clients — created once, reused for all S3 operations
    private S3Client    s3Client;
    private S3Presigner s3Presigner;

    private boolean s3Enabled = false;

    @PostConstruct
    private void init() {
        if ("NOT_SET".equals(accessKeyId) || "NOT_SET".equals(secretKey)
                || accessKeyId == null || secretKey == null) {
            log.warn("S3Service disabled — AWS credentials not configured. " +
                    "Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY to enable photo uploads.");
            return;
        }
        try {
            StaticCredentialsProvider cp = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretKey));
            Region r = Region.of(region);

            // Build S3 client — supports both real AWS and S3-compatible providers
            // (Supabase, MinIO, R2, DigitalOcean Spaces).
            // forcePathStyle=true is required for all non-AWS providers.
            var s3Builder = S3Client.builder()
                    .region(r)
                    .credentialsProvider(cp)
                    .forcePathStyle(true);  // required for Supabase, MinIO, R2

            var presignBuilder = S3Presigner.builder()
                    .region(r)
                    .credentialsProvider(cp);

            if (endpointOverride != null && !endpointOverride.isBlank()) {
                URI endpoint = URI.create(endpointOverride.trim());
                s3Builder      = s3Builder.endpointOverride(endpoint);
                presignBuilder = presignBuilder.endpointOverride(endpoint);
                log.info("S3Service using custom endpoint: {}", endpointOverride);
            }

            this.s3Client    = s3Builder.build();
            this.s3Presigner = presignBuilder.build();

            this.s3Enabled = true;
            log.info("S3Service initialised — region={} bucket={} endpoint={}",
                    region, bucket,
                    (endpointOverride != null && !endpointOverride.isBlank())
                            ? endpointOverride : "AWS default");
        } catch (Exception e) {
            log.error("S3Service failed to initialise: {} — photo uploads disabled", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (s3Client    != null) s3Client.close();
        if (s3Presigner != null) s3Presigner.close();
        log.info("S3Service shutdown — clients closed");
    }

    /** Upload a MultipartFile to S3. Returns the S3 object key. */
    public String upload(String folder, MultipartFile file) throws IOException {
        if (!s3Enabled || s3Client == null) {
            throw new IllegalStateException(
                    "S3 is not configured. Set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, " +
                            "and aws.s3.bucket in application.yml (or env vars) to enable photo uploads.");
        }
        String ext = getExtension(file.getOriginalFilename());
        String key = folder + "/" + UUID.randomUUID() + "." + ext;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromBytes(file.getBytes()));

        log.info("Uploaded to S3: s3://{}/{}", bucket, key);
        return key;
    }

    /** Generate a presigned GET URL valid for expiryHours. */
    public String generatePresignedUrl(String s3Key) {
        if (!s3Enabled || s3Presigner == null) {
            throw new IllegalStateException(
                    "S3 is not configured — cannot generate presigned URL. " +
                            "Set AWS credentials to enable photo uploads.");
        }
        return s3Presigner.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofHours(expiryHours))
                                .getObjectRequest(GetObjectRequest.builder()
                                        .bucket(bucket).key(s3Key).build())
                                .build())
                .url().toString();
    }

    /** Delete an object from S3 (used when replacing a photo). */
    public void delete(String s3Key) {
        if (!s3Enabled) { log.warn("S3 delete skipped — S3 not configured"); return; }
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(s3Key).build());
            log.info("Deleted from S3: s3://{}/{}", bucket, s3Key);
        } catch (Exception e) {
            log.warn("S3 delete failed for key {}: {}", s3Key, e.getMessage());
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
