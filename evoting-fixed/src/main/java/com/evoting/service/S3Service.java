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
    @Value("${aws.s3.presigned-url-expiry-hours:168}") private int  expiryHours;

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

            this.s3Client = S3Client.builder()
                    .region(r).credentialsProvider(cp).build();

            this.s3Presigner = S3Presigner.builder()
                    .region(r).credentialsProvider(cp).build();

            this.s3Enabled = true;
            log.info("S3Service initialised — region={} bucket={}", region, bucket);
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
