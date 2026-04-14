package com.evoting.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM for terminal packet encryption/decryption.
 * EC signature verification for JCOP4 card cryptographic assertions.
 *
 * ── FIX BUG 2: Election-scoped burn proof ────────────────────────────────────
 *
 * BEFORE:
 *   verifyCardSignature(voterPublicKey, packet.getCardIdHash(), burnProof)
 *   // The applet signed only cardIdHash. A burn proof from Election A is
 *   // cryptographically identical to one from Election B for the same voter.
 *   // Server-side check (findByCardIdHashAndElectionId) prevents replay, but
 *   // the cryptographic proof has no binding to a specific election.
 *
 * AFTER (this fix — server side):
 *   verifyCardSignature(voterPublicKey,
 *       buildBurnProofPayload(cardIdHash, electionId), burnProof)
 *   // Signed payload: cardIdHash + "|" + electionId.toString()
 *   // A proof from Election A cannot be used for Election B.
 *
 * NOTE: The JCOP4 applet (INS_SET_VOTED 0x51) must also be updated to sign
 *   SHA256(cardIdHash_bytes || "|" || electionId_bytes) instead of cardIdHash
 *   alone. See the companion JavaCard fix in JCOP4_APPLET_FIX.md.
 *
 * ── FIX DQ5: Ephemeral ECDH session key derivation ───────────────────────────
 *
 * The static AES key used for all terminal communications provides no forward
 * secrecy — a compromised terminal exposes all past sessions. This class now
 * provides:
 *
 *   generateServerEphemeralKeyPair()     → KeyPair (P-256)
 *   deriveSessionKey(serverPriv, terminalPub, sessionToken)
 *       → 32-byte AES key via ECDH + HKDF-SHA256
 *   decryptWithKey(base64Payload, sessionKeyBytes)
 *       → plaintext, using the derived key instead of the static key
 *
 * The protocol (auth-time ECDH exchange) is described in EphemeralKeyService.java.
 */
@Service
public class CryptoService {

    private static final String AES_ALGO  = "AES/GCM/NoPadding";
    private static final int    NONCE_LEN = 12;
    private static final int    TAG_BITS  = 128;

    @Value("${security.aes.secret-key}")
    private String base64AesKey;

    // ── Standard decrypt with static key (used by non-ephemeral paths) ────────

    /** Decrypts Base64( nonce[12] || ciphertext || tag[16] ) using the static AES key */
    public byte[] decrypt(String base64Payload) throws Exception {
        return decryptWithKey(base64Payload, Base64.getDecoder().decode(base64AesKey));
    }

    // ── Decrypt with an arbitrary key (used by ephemeral session key path) ────

    /**
     * Decrypts Base64( nonce[12] || ciphertext || tag[16] ) using the provided key bytes.
     * Called by VoteProcessingService when an ephemeral session key exists for the session.
     */
    public byte[] decryptWithKey(String base64Payload, byte[] keyBytes) throws Exception {
        byte[] raw   = Base64.getDecoder().decode(base64Payload);
        byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
        byte[] body  = Arrays.copyOfRange(raw, NONCE_LEN, raw.length);
        Cipher c = Cipher.getInstance(AES_ALGO);
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        return c.doFinal(body);
    }

    /** Encrypts plaintext → Base64( nonce[12] || ciphertext || tag[16] ) using the static key */
    public String encrypt(byte[] plaintext) throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance(AES_ALGO);
        c.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_BITS, nonce));
        byte[] cipher = c.doFinal(plaintext);
        byte[] out = new byte[NONCE_LEN + cipher.length];
        System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
        System.arraycopy(cipher, 0, out, NONCE_LEN, cipher.length);
        return Base64.getEncoder().encodeToString(out);
    }

    // ── FIX BUG 2: Election-scoped burn proof helpers ─────────────────────────

    /**
     * Constructs the canonical payload that the JCOP4 applet signs during SET_VOTED.
     *
     * Format:  cardIdHash + "|" + electionId
     *
     * The pipe separator is safe because cardIdHash is a hex string (0-9, a-f only)
     * and electionId is a UUID (0-9, a-f, hyphens only) — neither contains "|".
     *
     * The JCOP4 applet must produce this combined payload as its signed input.
     * See companion note: JCOP4_APPLET_FIX.md
     */
    public static String buildBurnProofPayload(String cardIdHash, java.util.UUID electionId) {
        return cardIdHash + "|" + electionId.toString();
    }

    /**
     * Verifies an EC signature produced by the JCOP4 Smart Card.
     *
     * @param publicKeyBase64 Voter's EC public key stored in voter_registry (X509, Base64)
     * @param message         The canonical signed message (use buildBurnProofPayload())
     * @param signatureBase64 Base64-encoded ECDSA signature from card (P1363 format)
     */
    public boolean verifyCardSignature(String publicKeyBase64, String message,
                                       String signatureBase64) throws Exception {
        PublicKey pub = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(publicKeyBase64)));
        // P1363 format matches Web Crypto and the JCOP4 applet output
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initVerify(pub);
        sig.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(signatureBase64));
    }

    // ── FIX DQ5: ECDH ephemeral key pair and session key derivation ──────────

    /**
     * Generates an ephemeral EC P-256 key pair for one ECDH exchange.
     * Called once per authentication session; the private key is discarded
     * after key derivation (never persisted).
     */
    public KeyPair generateServerEphemeralKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return kpg.generateKeyPair();
    }

    /**
     * Derives a 32-byte AES session key from an ECDH shared secret.
     *
     * Steps:
     *   1. ECDH(serverEphemeralPrivKey, terminalEphemeralPubKey) → sharedSecret (32 bytes)
     *   2. HKDF-SHA256(sharedSecret, salt=sessionToken, info="evoting-session-key") → 32 bytes
     *
     * The sessionToken is used as the HKDF salt to bind the derived key to a
     * specific voting session. Even if the ECDH shared secret is somehow recovered,
     * the derived key is session-unique and cannot be reused.
     *
     * @param serverPrivKeyBase64 Base64 PKCS8 private key (generated by generateServerEphemeralKeyPair)
     * @param terminalPubKeyBase64 Base64 X509 public key sent by the terminal in X-Ephemeral-Pub
     * @param sessionToken        The session token issued by the authentication flow
     * @return 32-byte AES key
     */
    public byte[] deriveSessionKey(String serverPrivKeyBase64,
                                   String terminalPubKeyBase64,
                                   String sessionToken) throws Exception {
        // Decode private key
        byte[] privBytes = Base64.getDecoder().decode(serverPrivKeyBase64);
        PrivateKey serverPriv = KeyFactory.getInstance("EC")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(privBytes));

        // Decode terminal's ephemeral public key
        byte[] pubBytes = Base64.getDecoder().decode(terminalPubKeyBase64);
        PublicKey termPub = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(pubBytes));

        // ECDH key agreement → raw shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(serverPriv);
        ka.doPhase(termPub, true);
        byte[] sharedSecret = ka.generateSecret();

        // HKDF-SHA256 extract + expand
        return hkdfSha256(sharedSecret,
                sessionToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "evoting-session-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                32);
    }

    /**
     * Minimal HKDF-SHA256 (RFC 5869) without adding a Bouncy Castle dependency.
     *
     * extract: prk = HMAC-SHA256(salt, ikm)
     * expand:  okm = T(1) where T(1) = HMAC-SHA256(prk, info || 0x01)
     *
     * This is sufficient for deriving a single 32-byte key (single expand round).
     */
    private byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outputLen)
            throws Exception {
        // Extract
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(ikm);

        // Expand (single round — sufficient for outputLen ≤ 32)
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        hmac.update(info);
        hmac.update((byte) 0x01);
        byte[] okm = hmac.doFinal();
        return Arrays.copyOf(okm, outputLen);
    }

    private SecretKeySpec buildKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(base64AesKey), "AES");
    }
}
