package com.evoting.service;

import com.evoting.model.VoterDemographics;
import com.evoting.repository.VoterDemographicsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DemographicsService — encrypted demographic storage and log-gated decryption.
 *
 * Every call to decrypt() writes an audit_log entry with who accessed the data,
 * when, and for which voter. The encryption key (DEMOGRAPHICS_KEY) is injected
 * from environment variables and never stored in the database.
 *
 * pgp_sym_encrypt / pgp_sym_decrypt via PostgreSQL pgcrypto extension.
 * The cipher options use AES256 with S2K iterations for key stretching.
 */
@Service @Slf4j
public class DemographicsService {

    private static final String PGP_OPTIONS =
            "cipher-algo=aes256, compress-algo=0, s2k-mode=3, s2k-count=65536";

    @Value("${security.demographics-key:CHANGE_THIS_KEY_IN_PRODUCTION}")
    private String demographicsKey;

    @Autowired private VoterDemographicsRepository demoRepo;
    @Autowired private AuditLogService              auditLog;
    @Autowired private JdbcTemplate                jdbc;

    /**
     * Encrypt and store demographics for a voter.
     * Called once during voter registration commit.
     *
     * @param voterId UUID of the voter_registry record
     * @param plainJson JSON string: {"firstName":"...","surname":"...","dob":"...","gender":"..."}
     */
    @Transactional
    public void storeEncrypted(UUID voterId, String plainJson) {
        // Use PostgreSQL pgp_sym_encrypt for AES-256 encryption
        String encrypted = jdbc.queryForObject(
                "SELECT pgp_sym_encrypt(?::text, ?::text, ?::text)",
                String.class,
                plainJson, demographicsKey, PGP_OPTIONS
        );

        VoterDemographics demo = VoterDemographics.builder()
                .voterId(voterId)
                .encryptedData(encrypted)
                .build();

        demoRepo.save(demo);
        log.info("[DEMOGRAPHICS] Stored encrypted demographics for voter {}", voterId);
    }

    /**
     * Decrypt and return demographics — every call is audit logged.
     *
     * @param voterId  the voter whose demographics to retrieve
     * @param accessor username of the admin requesting decryption
     * @return plaintext JSON string, or null if no record found
     */
    @Transactional
    public String decryptAndLog(UUID voterId, String accessor) {
        Optional<VoterDemographics> opt = demoRepo.findByVoterId(voterId);
        if (opt.isEmpty()) return null;

        VoterDemographics demo = opt.get();

        // Decrypt using pgcrypto
        String plainJson = jdbc.queryForObject(
                "SELECT pgp_sym_decrypt(?::bytea, ?::text)",
                String.class,
                demo.getEncryptedData(), demographicsKey
        );

        // Update access tracking
        demo.setLastAccessedBy(accessor);
        demo.setLastAccessedAt(OffsetDateTime.now());
        demo.setAccessCount(demo.getAccessCount() + 1);
        demoRepo.save(demo);

        // MANDATORY audit log — every decryption is permanently recorded
        auditLog.log("DEMOGRAPHICS_ACCESSED", accessor,
                "VoterId=" + voterId + " AccessCount=" + demo.getAccessCount());

        log.warn("[DEMOGRAPHICS] DECRYPTED for voter {} by {} (access #{})",
                voterId, accessor, demo.getAccessCount());

        return plainJson;
    }

    /**
     * Check if demographics exist for a voter without decrypting.
     */
    public boolean hasEncryptedDemographics(UUID voterId) {
        return demoRepo.findByVoterId(voterId).isPresent();
    }
}
