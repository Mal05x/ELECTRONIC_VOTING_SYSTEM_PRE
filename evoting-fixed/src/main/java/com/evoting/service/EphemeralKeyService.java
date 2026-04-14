package com.evoting.service;

import com.evoting.security.CryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * EphemeralKeyService — per-session ECDH key exchange for forward secrecy.
 *
 * ── Protocol ─────────────────────────────────────────────────────────────────
 *
 * The static AES-256 pre-shared key means that if a terminal is physically seized,
 * ALL past vote submissions (captured on the network) can be decrypted. This service
 * adds forward secrecy by giving each voting session its own derived AES key.
 *
 * Flow (runs during the authenticate phase, before vote submission):
 *
 *   1. Terminal generates an ephemeral EC P-256 key pair.
 *   2. Terminal includes its ephemeral public key in the auth request:
 *        Header: X-Ephemeral-Pub: <Base64 X509 public key>
 *
 *   3. AuthenticationService calls initSessionKey(sessionToken, terminalEphemeralPubB64).
 *   4. This service generates a server-side ephemeral key pair.
 *   5. Derives:
 *        sharedSecret = ECDH(serverEphemPriv, terminalEphemPub)
 *        sessionKey   = HKDF-SHA256(sharedSecret, sessionToken, "evoting-session-key")
 *   6. Stores sessionKey in Redis keyed by SHA256(sessionToken), TTL = 10 minutes.
 *   7. Returns the server's ephemeral public key (Base64 X509).
 *
 *   8. Auth response includes:
 *        { ..., "serverEphemeralPub": "<Base64 X509>" }
 *
 *   9. Terminal performs the same ECDH + HKDF with its own private key and the
 *      server's public key → arrives at the identical sessionKey.
 *
 *  10. Terminal encrypts the vote packet using sessionKey (AES-256-GCM).
 *
 *  11. VoteProcessingService calls getAndConsumeSessionKey(sessionToken) to
 *      retrieve the key, decrypt the packet, then delete the key from Redis.
 *      After decryption, the ephemeral keys are gone — the session is sealed.
 *
 * ── Backward Compatibility ────────────────────────────────────────────────────
 *
 * If no X-Ephemeral-Pub header is present (older firmware), initSessionKey()
 * returns null and VoteProcessingService falls back to the static AES key.
 * Plan to remove the fallback once all terminals are updated.
 *
 * ── ESP32 Firmware Notes ─────────────────────────────────────────────────────
 *
 * The ESP32-S3 must:
 *   - Generate an EC P-256 ephemeral key pair using mbedTLS:
 *       mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, ...)
 *   - Export the public key as X.509 SubjectPublicKeyInfo (DER), Base64-encode it.
 *   - Perform ECDH key agreement with the server's returned ephemeral pub key.
 *   - Derive sessionKey using HKDF-SHA256 with the sessionToken as salt.
 *   - Encrypt the vote packet with sessionKey before transmitting.
 *   - Discard the ephemeral private key immediately after derivation.
 */
@Service
@Slf4j
public class EphemeralKeyService {

    private static final String SESSION_KEY_PREFIX = "ephemkey:";
    /** Must match the voting session TTL — keys expire when their session expires. */
    private static final long   SESSION_KEY_TTL_MINUTES = 10L;

    @Autowired private CryptoService        crypto;
    @Autowired private StringRedisTemplate  redis;

    /**
     * Called during authentication when the terminal provides an ephemeral public key.
     *
     * @param sessionToken          The newly issued voting session token
     * @param terminalEphemPubB64   Base64 X509 ephemeral public key from the terminal
     *                              (header X-Ephemeral-Pub). Null/blank → skip (no forward secrecy).
     * @return Base64 X509 server ephemeral public key to include in the auth response,
     *         or null if no terminal key was provided.
     */
    public String initSessionKey(String sessionToken, String terminalEphemPubB64) {
        if (terminalEphemPubB64 == null || terminalEphemPubB64.isBlank()) {
            log.debug("[EphemeralKey] No terminal ephemeral key — skipping ECDH (legacy terminal).");
            return null;
        }
        try {
            KeyPair serverPair = crypto.generateServerEphemeralKeyPair();

            // Derive and store the session key
            String serverPrivB64 = Base64.getEncoder()
                    .encodeToString(serverPair.getPrivate().getEncoded());
            byte[] sessionKey = crypto.deriveSessionKey(
                    serverPrivB64, terminalEphemPubB64, sessionToken);

            // Store as hex in Redis — TTL slightly exceeds the session TTL
            String redisKey = SESSION_KEY_PREFIX + com.evoting.model.AuditLog.sha256(sessionToken);
            redis.opsForValue().set(redisKey,
                    bytesToHex(sessionKey),
                    SESSION_KEY_TTL_MINUTES, TimeUnit.MINUTES);

            String serverPubB64 = Base64.getEncoder()
                    .encodeToString(serverPair.getPublic().getEncoded());

            log.info("[EphemeralKey] Session key derived and stored for session hash {}",
                    redisKey.substring(SESSION_KEY_PREFIX.length(), SESSION_KEY_PREFIX.length() + 8) + "...");
            return serverPubB64;

        } catch (Exception e) {
            log.error("[EphemeralKey] ECDH key derivation failed: {}", e.getMessage());
            // Do not propagate — fall back to static key on the vote processing side
            return null;
        }
    }

    /**
     * Retrieves and immediately deletes the session key for the given session token.
     *
     * The delete-on-retrieval ensures that even if Redis is later dumped, the
     * derived key is no longer present — the session is permanently sealed.
     *
     * @param sessionToken The session token from the vote packet
     * @return 32-byte AES session key, or null if no ephemeral key exists
     *         (legacy terminal — caller falls back to static key).
     */
    public byte[] getAndConsumeSessionKey(String sessionToken) {
        String redisKey = SESSION_KEY_PREFIX + com.evoting.model.AuditLog.sha256(sessionToken);
        String hexKey   = redis.opsForValue().getAndDelete(redisKey);
        if (hexKey == null) {
            log.debug("[EphemeralKey] No ephemeral key for token hash — using static key (legacy).");
            return null;
        }
        log.debug("[EphemeralKey] Session key consumed and deleted for token hash.");
        return hexToBytes(hexKey);
    }

    private static String bytesToHex(byte[] bytes) {
        java.util.HexFormat hf = java.util.HexFormat.of();
        return hf.formatHex(bytes);
    }

    private static byte[] hexToBytes(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }
}
