package com.evoting.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM for terminal packet encryption/decryption.
 * EC signature verification for JCOP4 card cryptographic assertions.
 */
@Service
public class CryptoService {

    private static final String AES_ALGO  = "AES/GCM/NoPadding";
    private static final int    NONCE_LEN = 12;
    private static final int    TAG_BITS  = 128;

    @Value("${security.aes.secret-key}")
    private String base64AesKey;

    /** Decrypts Base64( nonce[12] || ciphertext || tag[16] ) */
    public byte[] decrypt(String base64Payload) throws Exception {
        byte[] raw   = Base64.getDecoder().decode(base64Payload);
        byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
        byte[] body  = Arrays.copyOfRange(raw, NONCE_LEN, raw.length);
        Cipher c = Cipher.getInstance(AES_ALGO);
        c.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_BITS, nonce));
        return c.doFinal(body);
    }

    /** Encrypts plaintext → Base64( nonce[12] || ciphertext || tag[16] ) */
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

    /**
     * Verifies EC signature produced by the JCOP4 Smart Card.
     * The card signs a canonical assertion string with its private key.
     * @param publicKeyBase64 Voter's EC public key stored in voter_registry (X509, Base64)
     * @param message         The canonical signed message
     * @param signatureBase64 Base64-encoded ECDSA signature from card
     */
    public boolean verifyCardSignature(String publicKeyBase64, String message,
                                       String signatureBase64) throws Exception {
        PublicKey pub = KeyFactory.getInstance("EC")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(pub);
        sig.update(message.getBytes());
        return sig.verify(Base64.getDecoder().decode(signatureBase64));
    }

    private SecretKeySpec buildKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(base64AesKey), "AES");
    }
}
