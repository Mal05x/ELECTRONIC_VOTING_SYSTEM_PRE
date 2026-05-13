package com.evoting.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.*;

/**
 * ============================================================
 * E-Voting Smart Card Applet — v2.3
 * Target : JCOP 4 (JavaCard 3.0.5 / GlobalPlatform 2.3)
 * AID    : A0:00:00:00:03:45:56:4F:54:45
 *
 * Changes from v2.2:
 *
 * FIX-1 — personalize() write acceptance response.
 *   PROBLEM: personalize() returned SW_9000 with no data.  The terminal
 *   had no cryptographic proof that adminTokenHash, cardStaticKey, PIN
 *   hash, or fingerprint template were written correctly.  A transient
 *   EEPROM write fault would silently produce a card that fails at the
 *   polling unit.
 *   FIX: After writing all fields, personalize() returns a 32-byte
 *   SHA-256 commitment over the stored values:
 *     commitment = SHA-256(cardStaticKey[16] || storedPINHash[32] ||
 *                          voterID[32] || adminTokenHash[32])
 *   The terminal verifies this commitment against its own locally-computed
 *   SHA-256 over the same inputs before considering enrollment complete.
 *   fingerprintTemplate is excluded to keep the scratchPad within the
 *   512-byte limit; the fingerprint is verified separately via
 *   INS_VERIFY_FINGERPRINT immediately after personalization.
 *
 * FIX-2 — verifyFingerprint() CBC with transmitted IV.
 *   PROBLEM: verifyFingerprint() always decrypted with zero IV
 *   (aesCBCDecryptZeroIV), ignoring P1 entirely.  The terminal's
 *   FINGERPRINT_USE_CBC=1 path sends P1=0x01 with IV(16 bytes) prepended
 *   to the ciphertext, making Lc = 16 + encrypted_len = up to 528 bytes.
 *   The applet rejected Lc > 512 with SW_WRONG_LENGTH every time.
 *   FIX: When P1 == 0x01, the applet reads the first 16 bytes of CDATA
 *   as the IV, then decrypts the remaining (Lc-16) bytes using that IV.
 *   When P1 == 0x00 (legacy), it falls back to zero IV (backward compat).
 *
 * FIX-3 — checkVoteStatus() requires PIN validation.
 *   PROBLEM: checkVoteStatus() required only a secure channel.  A rogue
 *   terminal with custom firmware could determine "has this voter voted?"
 *   by tapping the card without PIN authentication, enabling voter-status
 *   surveillance without the voter's knowledge or consent.
 *   FIX: checkVoteStatus() now checks pinValidatedThisSession and throws
 *   SW_PIN_REQUIRED (0x6301) if PIN has not been verified.  The terminal
 *   firmware already enforces this on its side (v3 firmware gate); this
 *   fix moves the enforcement into the card itself where it cannot be
 *   bypassed by any terminal firmware.
 *
 * FIX-4 — lockCard() ECB decryption for 32-byte admin token.
 *   PROBLEM: lockCard() called aesCBCDecryptZeroIV on the 32-byte
 *   encrypted admin token.  CBC chains block N-1 ciphertext into block N
 *   decryption.  The terminal (sc_lock_card_admin_token) encrypts the two
 *   16-byte halves independently with AES-ECB (two separate
 *   mbedtls_aes_crypt_ecb calls).  With CBC, the second block decrypts to
 *   plaintext[16..31] XOR ciphertext[0..15] instead of plaintext[16..31].
 *   The SHA-256 of the mangled 32 bytes never matches adminTokenHash.
 *   Card locking was silently broken for any rawAdminToken where
 *   bytes[16..31] != 0.
 *   FIX: lockCard() now uses aesECBDecryptBlocks() — two independent ECB
 *   decryptions matching the terminal's two mbedtls_aes_crypt_ecb calls.
 *   New helper aesECBDecryptBlocks(src, srcOff, dst, dstOff, blocks) added.
 *
 * Inherited from v2.2:
 *   Q3 — Admin token card decommission (lockCard requires adminTokenHash).
 *   BUG-2 — Election-scoped burn proof in setVoted().
 *   B-01..B-07 — See v2.2 header.
 * ============================================================
 */
public class MFAVotingApplet extends Applet {

    // ==================== INSTRUCTIONS ====================
    private static final byte CLA_EVOTING               = (byte) 0x80;
    private static final byte INS_PERSONALIZE            = (byte) 0x10;
    private static final byte INS_VERIFY_PIN             = (byte) 0x20;
    private static final byte INS_STORE_FINGERPRINT      = (byte) 0x30;
    private static final byte INS_VERIFY_FINGERPRINT     = (byte) 0x31;
    private static final byte INS_STORE_LIVENESS         = (byte) 0x32;  // enrollment: store 256-byte embedding
    private static final byte INS_GET_VOTER_ID           = (byte) 0x40;
    private static final byte INS_GET_LIVENESS           = (byte) 0x43;  // voting: retrieve 256-byte embedding
    private static final byte INS_CHECK_VOTE_STATUS      = (byte) 0x50;
    private static final byte INS_SET_VOTED              = (byte) 0x51;
    private static final byte INS_INIT_SECURE_CHANNEL    = (byte) 0x60;
    private static final byte INS_ESTABLISH_SESSION      = (byte) 0x61;
    private static final byte INS_GET_CHALLENGE          = (byte) 0x70;
    private static final byte INS_GET_SIGNATURE          = (byte) 0x71;
    private static final byte INS_GET_PUBLIC_KEY         = (byte) 0x72;
    private static final byte INS_WRITE_VOTER_CREDENTIAL = (byte) 0x80;
    private static final byte INS_LOCK_CARD              = (byte) 0x90;

    // ==================== STATUS WORDS ====================
    private static final short SW_PIN_VERIFICATION_REQUIRED      = 0x6300;
    private static final short SW_PIN_REQUIRED                   = 0x6301; // FIX-3
    private static final short SW_PIN_BLOCKED                    = (short) 0x6983;
    private static final short SW_ALREADY_VOTED                  = (short) 0x6A81;
    private static final short SW_FINGERPRINT_NOT_MATCH          = (short) 0x6A82;
    private static final short SW_SECURE_CHANNEL_NOT_ESTABLISHED = (short) 0x6982;
    private static final short SW_CONDITIONS_NOT_SATISFIED       = (short) 0x6985;
    private static final short SW_CARD_LOCKED                    = (short) 0x6986;
    private static final short SW_NOT_PERSONALIZED               = (short) 0x6987;

    // ==================== SIZES ====================
    private static final short VOTER_ID_SIZE              = 32;
    private static final short FINGERPRINT_TEMPLATE_SIZE  = 512;
    private static final short SESSION_KEY_SIZE           = 16;
    private static final short CHALLENGE_SIZE             = 16;
    private static final short PIN_HASH_SIZE              = 32;
    private static final short STATIC_KEY_SIZE            = 16;
    private static final short IV_SIZE                    = 16;
    private static final short ADMIN_TOKEN_SIZE           = 32;
    private static final short COMMITMENT_SIZE            = 32;
    private static final short LIVENESS_EMBEDDING_SIZE    = 256;  // MiniFASNetV2_SE 8×8 grid, uint8-quantized
    private static final byte  MAX_PIN_TRIES              = (byte) 3;
    private static final short MATCH_THRESHOLD_PERCENT    = (short) 80;
    private static final short ELECTION_ID_SIZE           = 36;

    // P1 values for INS_VERIFY_FINGERPRINT
    private static final byte FP_MODE_ZERO_IV = (byte) 0x00; // legacy
    private static final byte FP_MODE_CBC_IV  = (byte) 0x01; // FIX-2

    private static final byte[] SIGNED_MESSAGE = {
        'I','d','e','n','t','i','t','y',' ','C','r','y','p','t','o',
        'g','r','a','p','h','i','c','a','l','l','y',' ','V','e','r','i','f','i','e','d'
    };

    // ==================== PERSISTENT EEPROM ====================
    private byte[]  voterID;
    private byte[]  storedPINHash;
    private byte[]  fingerprintTemplate;
    private byte[]  cardStaticKey;
    private byte[]  adminTokenHash;
    private byte[]  lastElectionId;
    private byte[]  livenessEmbedding;   // MiniFASNetV2_SE reference embedding (256 bytes)
    private boolean personalized;
    private boolean locked;
    private boolean fingerprintStored;
    private boolean livenessStored;      // true after INS_STORE_LIVENESS succeeds
    private byte    pinTriesRemaining;

    // ==================== SESSION (TRANSIENT) ====================
    private byte[]  sessionKey;
    private byte[]  currentChallenge;
    private byte[]  adhocChallenge;
    private byte[]  scratchPad;
    private byte[]  ivBuffer;
    private boolean secureChannelEstablished;
    private boolean fingerprintVerifiedThisSession;
    private boolean pinValidatedThisSession;

    // ==================== CRYPTO ====================
    private AESKey      aesKey;
    private Cipher      aesCipher;
    private RandomData  randomGenerator;
    private MessageDigest sha256;
    private Signature   ecdsaSignature;
    private KeyPair     ecKeyPair;

    // ==================== CONSTRUCTOR ====================
    private MFAVotingApplet() {
        voterID              = new byte[VOTER_ID_SIZE];
        storedPINHash        = new byte[PIN_HASH_SIZE];
        fingerprintTemplate  = new byte[FINGERPRINT_TEMPLATE_SIZE];
        cardStaticKey        = new byte[STATIC_KEY_SIZE];
        adminTokenHash       = new byte[ADMIN_TOKEN_SIZE];
        lastElectionId       = new byte[ELECTION_ID_SIZE];
        livenessEmbedding    = new byte[LIVENESS_EMBEDDING_SIZE];

        personalized      = false;
        locked            = false;
        fingerprintStored = false;
        livenessStored    = false;
        pinTriesRemaining = MAX_PIN_TRIES;

        sessionKey       = JCSystem.makeTransientByteArray(SESSION_KEY_SIZE,  JCSystem.CLEAR_ON_DESELECT);
        currentChallenge = JCSystem.makeTransientByteArray(CHALLENGE_SIZE,    JCSystem.CLEAR_ON_DESELECT);
        adhocChallenge   = JCSystem.makeTransientByteArray(CHALLENGE_SIZE,    JCSystem.CLEAR_ON_DESELECT);
        scratchPad       = JCSystem.makeTransientByteArray((short) 512,       JCSystem.CLEAR_ON_DESELECT);
        ivBuffer         = JCSystem.makeTransientByteArray(IV_SIZE,           JCSystem.CLEAR_ON_DESELECT);

        secureChannelEstablished       = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession        = false;

        try {
            aesKey         = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
            aesCipher      = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
            randomGenerator= RandomData.getInstance(RandomData.ALG_KEYGENERATION);
            sha256         = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
            ecKeyPair      = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            ecdsaSignature = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
        } catch (CryptoException e) {
            SystemException.throwIt(SystemException.NO_RESOURCE);
        }
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MFAVotingApplet().register(bArray, (short)(bOffset + 1), bArray[bOffset]);
    }

    public boolean select() {
        secureChannelEstablished       = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession        = false;
        if (ecdsaSignature != null && ecKeyPair != null && ecKeyPair.getPrivate().isInitialized()) {
            ecdsaSignature.init(ecKeyPair.getPrivate(), Signature.MODE_SIGN);
        }
        return true;
    }

    public void deselect() {
        Util.arrayFillNonAtomic(scratchPad, (short) 0, (short) scratchPad.length, (byte) 0);
        Util.arrayFillNonAtomic(ivBuffer,   (short) 0, IV_SIZE, (byte) 0);
        secureChannelEstablished       = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession        = false;
    }

    // ==================== DISPATCH ====================
    public void process(APDU apdu) {
        if (selectingApplet()) return;
        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != CLA_EVOTING)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_PERSONALIZE:            personalize(apdu);         break;
            case INS_INIT_SECURE_CHANNEL:    initSecureChannel(apdu);   break;
            case INS_ESTABLISH_SESSION:      establishSession(apdu);    break;
            case INS_GET_CHALLENGE:          getChallenge(apdu);        break;
            case INS_VERIFY_PIN:             verifyPIN(apdu);           break;
            case INS_STORE_FINGERPRINT:      storeFingerprint(apdu);    break;
            case INS_VERIFY_FINGERPRINT:     verifyFingerprint(apdu);   break;
            case INS_STORE_LIVENESS:         storeLiveness(apdu);       break;
            case INS_GET_VOTER_ID:           getVoterID(apdu);          break;
            case INS_GET_LIVENESS:           getLiveness(apdu);         break;
            case INS_CHECK_VOTE_STATUS:      checkVoteStatus(apdu);     break;
            case INS_SET_VOTED:              setVoted(apdu);            break;
            case INS_GET_SIGNATURE:          generateSignature(apdu);   break;
            case INS_GET_PUBLIC_KEY:         getPublicKey(apdu);        break;
            case INS_WRITE_VOTER_CREDENTIAL: writeVoterCredential(apdu);break;
            case INS_LOCK_CARD:              lockCard(apdu);            break;
            default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // ==================== AES HELPERS ====================

    /** AES-128-CBC encrypt with zero IV. */
    private short aesCBCEncryptZeroIV(byte[] src, short srcOff, short len,
                                       byte[] dst, short dstOff) {
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        aesCipher.init(aesKey, Cipher.MODE_ENCRYPT, ivBuffer, (short) 0, IV_SIZE);
        return aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    /** AES-128-CBC decrypt with zero IV. */
    private void aesCBCDecryptZeroIV(byte[] src, short srcOff, short len,
                                      byte[] dst, short dstOff) {
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        aesCipher.init(aesKey, Cipher.MODE_DECRYPT, ivBuffer, (short) 0, IV_SIZE);
        aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    /**
     * AES-128-CBC decrypt with caller-supplied IV.
     * FIX-2: used by verifyFingerprint when P1=0x01.
     */
    private void aesCBCDecryptWithIV(byte[] iv,  short ivOff,
                                      byte[] src, short srcOff, short len,
                                      byte[] dst, short dstOff) {
        aesCipher.init(aesKey, Cipher.MODE_DECRYPT, iv, ivOff, IV_SIZE);
        aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    /**
     * AES-128-ECB decrypt: `blocks` independent 16-byte blocks.
     * FIX-4: used by lockCard for 32-byte admin token (2 blocks).
     *
     * JavaCard's ALG_AES_BLOCK_128_CBC_NOPAD with zero IV behaves as ECB
     * only for the first block (IV XOR = 0).  For block 2+ with CBC the
     * previous ciphertext block is XOR'd into the plaintext, which is NOT
     * what the terminal sends.  This helper re-initialises the cipher with
     * zero IV for each block, achieving true ECB behaviour.
     */
    private void aesECBDecryptBlocks(byte[] src, short srcOff,
                                      byte[] dst, short dstOff,
                                      short blocks) {
        for (short b = 0; b < blocks; b++) {
            Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
            aesCipher.init(aesKey, Cipher.MODE_DECRYPT, ivBuffer, (short) 0, IV_SIZE);
            aesCipher.doFinal(src, (short)(srcOff + b * 16), IV_SIZE,
                              dst, (short)(dstOff + b * 16));
        }
    }

    // ==================== PERSONALIZATION (FIX-1) ====================
    /**
     * INS_PERSONALIZE (0x10) — one-time card setup.
     *
     * Input (596 bytes):
     *   [0  .. 15 ] cardStaticKey      (16)
     *   [16 .. 19 ] raw PIN digits     ( 4) — SHA-256'd before storage
     *   [20 .. 51 ] voterID            (32)
     *   [52 .. 563] fingerprintTemplate(512)
     *   [564.. 595] adminTokenHash     (32) — SHA-256(rawAdminToken)
     *
     * Response (FIX-1) — 32-byte write-acceptance commitment:
     *   commitment = SHA-256(cardStaticKey[16] || storedPINHash[32] ||
     *                        voterID[32]       || adminTokenHash[32])
     *
     * The terminal MUST verify the commitment locally.  On mismatch
     * it must abort enrollment, deactivate the card, and alert the admin.
     * The fingerprint is verified separately via INS_VERIFY_FINGERPRINT.
     */
        private void personalize(APDU apdu) {
        if (personalized || locked)
            ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();

        final short EXPECTED = (short)(STATIC_KEY_SIZE + 4 + VOTER_ID_SIZE
                               + FINGERPRINT_TEMPLATE_SIZE + ADMIN_TOKEN_SIZE);
        if (lc != EXPECTED)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short off = ISO7816.OFFSET_CDATA;

        // Start Atomic EEPROM Write
        JCSystem.beginTransaction();

        Util.arrayCopy(buffer, off, cardStaticKey, (short) 0, STATIC_KEY_SIZE);
        off += STATIC_KEY_SIZE;

        sha256.reset();
        sha256.doFinal(buffer, off, (short) 4, storedPINHash, (short) 0);
        off += 4;

        Util.arrayCopy(buffer, off, voterID, (short) 0, VOTER_ID_SIZE);
        off += VOTER_ID_SIZE;

        Util.arrayCopy(buffer, off, fingerprintTemplate, (short) 0, FINGERPRINT_TEMPLATE_SIZE);
        off += FINGERPRINT_TEMPLATE_SIZE;

        Util.arrayCopy(buffer, off, adminTokenHash, (short) 0, ADMIN_TOKEN_SIZE);

        fingerprintStored = true;
        Util.arrayFillNonAtomic(lastElectionId, (short) 0, ELECTION_ID_SIZE, (byte) 0);
        pinTriesRemaining = MAX_PIN_TRIES;

        ecKeyPair.genKeyPair();
        ecdsaSignature.init(ecKeyPair.getPrivate(), Signature.MODE_SIGN);

        personalized = true;
        
        // Commit Atomic EEPROM Write
        JCSystem.commitTransaction();

        // Compute and return write-acceptance commitment
        sha256.reset();
        sha256.update(cardStaticKey,   (short) 0, STATIC_KEY_SIZE);
        sha256.update(storedPINHash,   (short) 0, PIN_HASH_SIZE);
        sha256.update(voterID,         (short) 0, VOTER_ID_SIZE);
        sha256.doFinal(adminTokenHash, (short) 0, ADMIN_TOKEN_SIZE,
                       scratchPad,     (short) 0);

        Util.arrayCopy(scratchPad, (short) 0, buffer, (short) 0, COMMITMENT_SIZE);
        apdu.setOutgoingAndSend((short) 0, COMMITMENT_SIZE);
    }


    // ==================== SECURE CHANNEL ====================
    private void initSecureChannel(APDU apdu) {
        if (!personalized)
            ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (locked)
            ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        randomGenerator.nextBytes(currentChallenge, (short) 0, CHALLENGE_SIZE);

        // sessionKey = SHA-256(termRandom || cardRandom || cardStaticKey)[0:16]
        sha256.reset();
        sha256.update(buffer, ISO7816.OFFSET_CDATA, CHALLENGE_SIZE);
        sha256.update(currentChallenge, (short) 0, CHALLENGE_SIZE);
        sha256.doFinal(cardStaticKey, (short) 0, STATIC_KEY_SIZE, scratchPad, (short) 0);
        Util.arrayCopy(scratchPad, (short) 0, sessionKey, (short) 0, SESSION_KEY_SIZE);
        aesKey.setKey(sessionKey, (short) 0);

        // Card cryptogram = AES-CBC-ZeroIV-Encrypt(sessionKey, cardRandom)
        aesCBCEncryptZeroIV(currentChallenge, (short) 0, CHALLENGE_SIZE, scratchPad, (short) 0);

        Util.arrayCopy(currentChallenge, (short) 0, buffer, (short) 0, CHALLENGE_SIZE);
        Util.arrayCopy(scratchPad, (short) 0, buffer, CHALLENGE_SIZE, CHALLENGE_SIZE);
        apdu.setOutgoingAndSend((short) 0, (short)(CHALLENGE_SIZE * 2));
    }

    private void establishSession(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Expected terminal cryptogram = AES-CBC-ZeroIV-Encrypt(sessionKey, termRand XOR cardRand)
        for (short i = 0; i < CHALLENGE_SIZE; i++) {
            scratchPad[i] = (byte)(buffer[(short)(ISO7816.OFFSET_CDATA + i)] ^ currentChallenge[i]);
        }
        aesCBCEncryptZeroIV(scratchPad, (short) 0, CHALLENGE_SIZE, scratchPad, CHALLENGE_SIZE);

        if (Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA,
                              scratchPad, CHALLENGE_SIZE, CHALLENGE_SIZE) == 0) {
            secureChannelEstablished = true;
        } else {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
    }

    private void getChallenge(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        randomGenerator.nextBytes(adhocChallenge, (short) 0, CHALLENGE_SIZE);
        Util.arrayCopy(adhocChallenge, (short) 0, buffer, (short) 0, CHALLENGE_SIZE);
        apdu.setOutgoingAndSend((short) 0, CHALLENGE_SIZE);
    }

    // ==================== PIN VERIFICATION ====================
        private void verifyPIN(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (pinTriesRemaining == 0)    ISOException.throwIt(SW_PIN_BLOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != PIN_HASH_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // 1. ATOMIC DECREMENT FIRST (Tearing Protection)
        JCSystem.beginTransaction();
        pinTriesRemaining--;
        JCSystem.commitTransaction();

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, PIN_HASH_SIZE, scratchPad, (short) 0);

        if (Util.arrayCompare(scratchPad, (short) 0, storedPINHash, (short) 0, PIN_HASH_SIZE) == 0) {
            // 2. RESTORE ON SUCCESS
            JCSystem.beginTransaction();
            pinTriesRemaining = MAX_PIN_TRIES;
            JCSystem.commitTransaction();
            
            pinValidatedThisSession = true;
        } else {
            // Return tries remaining in the lower nibble
            ISOException.throwIt((short)(0x6300 | (pinTriesRemaining & 0x0F)));
        }
    }


    // ==================== MATCH-ON-CARD (FIX-2) ====================
    /**
     * INS_VERIFY_FINGERPRINT (0x31)
     *
     * P1 = 0x00 (FP_MODE_ZERO_IV): legacy path — CBC with zero IV.
     *   Lc = encrypted_template_len (≤ 512)
     *   CDATA = AES-128-CBC-ZeroIV-Encrypt(sessionKey, template)
     *
     * P1 = 0x01 (FP_MODE_CBC_IV): new path — CBC with transmitted IV.
     *   Lc = 16 + encrypted_template_len (≤ 528)
     *   CDATA[0..15]    = IV (16 bytes, HMAC-derived at terminal)
     *   CDATA[16..Lc-1] = AES-128-CBC-Encrypt(sessionKey, IV, template)
     *
     * The terminal firmware sets FINGERPRINT_USE_CBC=1 to use P1=0x01.
     * Both paths produce the same match result — the IV only affects
     * ciphertext patterns, not the plaintext that is compared.
     */
        private void verifyFingerprint(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!fingerprintStored)        ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        byte   p1     = buffer[ISO7816.OFFSET_P1];
        short  lc     = apdu.setIncomingAndReceive();

        short decryptedLen;
        short dataOff;

        if (p1 == FP_MODE_CBC_IV) {
            if (lc < (short)(IV_SIZE + 16) || lc > (short)(IV_SIZE + FINGERPRINT_TEMPLATE_SIZE))
                ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            decryptedLen = (short)(lc - IV_SIZE);
            dataOff      = (short)(ISO7816.OFFSET_CDATA + IV_SIZE);
            aesCBCDecryptWithIV(buffer, ISO7816.OFFSET_CDATA,
                                buffer, dataOff, decryptedLen,
                                scratchPad, (short) 0);
        } else {
            if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
                ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            decryptedLen = lc;
            aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);
        }

        short totalBits    = (short)(decryptedLen * 8);
        short differentBits = 0;

        for (short i = 0; i < decryptedLen; i++) {
            byte v = (byte)(scratchPad[i] ^ fingerprintTemplate[i]);
            if ((v & (byte) 0x01) != 0) differentBits++;
            if ((v & (byte) 0x02) != 0) differentBits++;
            if ((v & (byte) 0x04) != 0) differentBits++;
            if ((v & (byte) 0x08) != 0) differentBits++;
            if ((v & (byte) 0x10) != 0) differentBits++;
            if ((v & (byte) 0x20) != 0) differentBits++;
            if ((v & (byte) 0x40) != 0) differentBits++;
            if ((v & (byte) 0x80) != 0) differentBits++;
        }

        short matchingBits = (short)(totalBits - differentBits);
        
        // Accurate Cross-Multiplication for 80% (4/5)
        short leftSide = (short)(matchingBits * 5);
        short rightSide = (short)(totalBits * 4);

        if (leftSide >= rightSide) {
            fingerprintVerifiedThisSession = true;
        } else {
            ISOException.throwIt(SW_FINGERPRINT_NOT_MATCH);
        }
    }


    // ==================== VOTER ID ====================
    private void getVoterID(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopy(voterID, (short) 0, buffer, (short) 0, VOTER_ID_SIZE);
        apdu.setOutgoingAndSend((short) 0, VOTER_ID_SIZE);
    }

    // ==================== VOTE STATUS (FIX-3) ====================
    /**
     * INS_CHECK_VOTE_STATUS (0x50)
     *
     * FIX-3: Now requires pinValidatedThisSession.
     * Returns SW_PIN_REQUIRED (0x6301) if PIN not verified.
     *
     * This moves the "PIN before status" gate into the card itself.
     * Even a rogue terminal with modified firmware cannot extract
     * voted status without first presenting the correct voter PIN.
     */
    private void checkVoteStatus(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_REQUIRED); // FIX-3

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != ELECTION_ID_SIZE)    ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if (Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA,
                              lastElectionId, (short) 0, ELECTION_ID_SIZE) == 0) {
            buffer[0] = (byte) 0x01;
        } else {
            buffer[0] = (byte) 0x00;
        }
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    // ==================== SET VOTED ====================
    private void setVoted(APDU apdu) {
        if (!secureChannelEstablished)         ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)          ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession)   ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc < (short) 38) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short uuidOffset = (short)((ISO7816.OFFSET_CDATA + lc) - ELECTION_ID_SIZE);

        if (Util.arrayCompare(buffer, uuidOffset, lastElectionId, (short) 0, ELECTION_ID_SIZE) == 0)
            ISOException.throwIt(SW_ALREADY_VOTED);

        // Sign BEFORE writing (atomicity — B-05)
        short sigLen = ecdsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);
        Util.arrayCopy(buffer, uuidOffset, lastElectionId, (short) 0, ELECTION_ID_SIZE);

        Util.arrayCopy(scratchPad, (short) 0, buffer, (short) 0, sigLen);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // ==================== AUTH SIGNATURE ====================
    private void generateSignature(APDU apdu) {
        if (!secureChannelEstablished)       ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)        ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != 1 || buffer[ISO7816.OFFSET_CDATA] != (byte) 0x01)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);

        short sigLen = ecdsaSignature.sign(SIGNED_MESSAGE, (short) 0,
                (short) SIGNED_MESSAGE.length, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // ==================== GET PUBLIC KEY ====================
    private void getPublicKey(APDU apdu) {
        if (ecKeyPair == null)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        byte[] buffer = apdu.getBuffer();
        ECPublicKey pubKey = (ECPublicKey) ecKeyPair.getPublic();
        short len = pubKey.getW(buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, len);
    }

    
        // ==================== FINGERPRINT STORAGE ====================
    private void storeFingerprint(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)                    ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Decrypt to RAM first
        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);
        
        // Atomic bulk write to EEPROM
        JCSystem.beginTransaction();
        Util.arrayCopy(scratchPad, (short) 0, fingerprintTemplate, (short) 0, lc);
        fingerprintStored = true;
        JCSystem.commitTransaction();
    }

    // ==================== LIVENESS EMBEDDING (v2.4) ====================

    /**
     * INS_STORE_LIVENESS (0x32) — write 256-byte face embedding to EEPROM.
     *
     * Called during enrollment AFTER INS_PERSONALIZE + INS_VERIFY_FINGERPRINT.
     * Requires: secureChannelEstablished AND pinValidatedThisSession.
     *
     * Input (extended APDU, Lc=256):
     *   AES-128-CBC-ZeroIV-Encrypt(sessionKey, embedding[256])
     *
     * The 256 bytes are the MiniFASNetV2_SE 8×8 grid face embedding,
     * quantized from float32 to uint8 (value = clamp(f * 255, 0, 255)).
     *
     * Why stored on card:
     *   The reference embedding must follow the voter — if it were stored
     *   server-side, every liveness check would require a network round-trip.
     *   Storing it on the card makes the full auth flow offline-capable.
     *   The embedding is encrypted during transport (session key) and is not
     *   directly useful without the card's EC private key for signing — it
     *   cannot be used in isolation to forge a liveness check.
     *
     * SW_SUCCESS on success. SW_WRONG_LENGTH if Lc ≠ 256.
     */
    // ==================== LIVENESS EMBEDDING ====================
    private void storeLiveness(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)                    ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();

        if (lc != LIVENESS_EMBEDDING_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Decrypt to RAM first
        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, LIVENESS_EMBEDDING_SIZE, scratchPad, (short) 0);
        
        // Atomic bulk write to EEPROM
        JCSystem.beginTransaction();
        Util.arrayCopy(scratchPad, (short) 0, livenessEmbedding, (short) 0, LIVENESS_EMBEDDING_SIZE);
        livenessStored = true;
        JCSystem.commitTransaction();
    }

        // ==================== GET LIVENESS ====================
    private void getLiveness(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!livenessStored)           ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();

        // Encrypt embedding under session key before returning
        aesCBCEncryptZeroIV(livenessEmbedding, (short) 0,
                            LIVENESS_EMBEDDING_SIZE,
                            buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, LIVENESS_EMBEDDING_SIZE);
    }


    // ==================== WRITE VOTER CREDENTIAL ====================
    private void writeVoterCredential(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)                    ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Decrypt to RAM first
        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);
        
        // Atomic bulk write to EEPROM
        JCSystem.beginTransaction();
        Util.arrayCopy(scratchPad, (short) 0, fingerprintTemplate, (short) 0, lc);
        fingerprintStored = true;
        JCSystem.commitTransaction();
    }


    // ==================== LOCK CARD (FIX-4) ====================
    /**
     * INS_LOCK_CARD (0x90) — permanent card decommission.
     *
     * Input: 32-byte admin token encrypted in two independent AES-128-ECB
     * blocks under the current session key.  This matches the terminal's
     * sc_lock_card_admin_token() which calls mbedtls_aes_crypt_ecb twice.
     *
     * FIX-4: Replaced aesCBCDecryptZeroIV with aesECBDecryptBlocks(2).
     * With CBC, block 2 decrypts to plaintext XOR ciphertext[0], not
     * plaintext — the SHA-256 of the mangled 32 bytes never matched
     * adminTokenHash and card locking was silently broken.
     */
    private void lockCard(APDU apdu) {
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != ADMIN_TOKEN_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // FIX-4: Decrypt two independent 16-byte ECB blocks
        aesECBDecryptBlocks(buffer, ISO7816.OFFSET_CDATA,
                            scratchPad, (short) 0,
                            (short) 2);

        // SHA-256(decrypted 32-byte raw token) must match stored adminTokenHash
        sha256.reset();
        sha256.doFinal(scratchPad, (short) 0, ADMIN_TOKEN_SIZE,
                       scratchPad, ADMIN_TOKEN_SIZE);

        if (Util.arrayCompare(scratchPad, ADMIN_TOKEN_SIZE,
                              adminTokenHash, (short) 0,
                              ADMIN_TOKEN_SIZE) != 0) {
            Util.arrayFillNonAtomic(scratchPad, (short) 0, (short)(ADMIN_TOKEN_SIZE * 2), (byte) 0);
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        Util.arrayFillNonAtomic(scratchPad, (short) 0, (short)(ADMIN_TOKEN_SIZE * 2), (byte) 0);
        locked = true;
    }
}
