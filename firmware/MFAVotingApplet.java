package com.evoting.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.*;

/**
 * ============================================================
 * E-Voting Smart Card Applet — v2.2 (Q3 + BUG-2 Fixes)
 * Target : JCOP 4 (JavaCard 3.0.5 / GlobalPlatform 2.3)
 * AID : A0:00:00:00:03:45:56:4F:54:45
 *
 * Changes from v2.1:
 *
 * Q3 — Admin token card decommission (voter PIN removed from lockCard).
 *
 * PROBLEM: lockCard() previously required pinValidatedThisSession,
 * meaning the voter had to be physically present and enter their PIN
 * to decommission a lost/stolen/returned card. Admins do not know
 * voter PINs. This made administrative card decommission impossible
 * without the voter's cooperation.
 *
 * FIX: lockCard() now accepts a 32-byte admin token (Lc=32) encrypted
 * with the card's current session key. The applet:
 * 1. Decrypts the received token with aesECBDecryptBlocks()
 * 2. SHA-256 hashes the plaintext
 * 3. Compares against adminTokenHash stored during personalization
 * 4. Match → locked = true (permanent EEPROM)
 * 5. No match → SW_SECURITY_STATUS_NOT_SATISFIED (9802)
 *
 * adminTokenHash is stored in a new persistent field and is written
 * during INS_PERSONALIZE as the last 32 bytes of the APDU payload.
 * personalize() EXPECTED size updated: +ADMIN_TOKEN_SIZE (+32 bytes).
 * New total: 596 bytes (was 564).
 *
 * The raw admin token is generated server-side by a SUPER_ADMIN
 * step-up action and transmitted to the terminal over mTLS.
 * It never appears in plaintext on the card.
 *
 * BUG-2 — Election-scoped burn proof in setVoted().
 *
 * PROBLEM: setVoted() signed only voterID (32-byte on-card constant).
 * A burn proof from Election A was cryptographically identical to one
 * from Election B for the same voter — no election binding in the proof.
 *
 * FIX: setVoted() now calls setIncomingAndReceive() to read the
 * combined payload sent by the terminal:
 * data = cardIdHash_bytes + 0x7C ("|") + electionId_bytes (UUID)
 * Minimum Lc = 38 bytes (validated before signing).
 * The applet signs whatever Lc data it receives. The server reconstructs
 * the identical string and verifies. Proofs are election-unique.
 *
 * Earlier fixes (v2.1):
 * B-01 scratchPad enlarged 256 → 512 bytes (fingerprint overflow fix).
 * B-02 Hamming-distance overflow eliminated (safe chunk-based comparison).
 * B-03 lockCard() requires secure channel (DoS prevention — retained).
 * B-04 writeVoterCredential() requires PIN (consistent with storeFingerprint).
 * B-05 setVoted() signs BEFORE writing hasVoted=true (atomic order fix).
 * B-06 Constructor CryptoException re-throws SystemException (fail-fast).
 * B-07 Dead code initializeCard() removed.
 * ============================================================
 */
public class MFAVotingApplet extends Applet {

    // ==================== INSTRUCTIONS ====================
    private static final byte CLA_EVOTING = (byte) 0x80;
    private static final byte INS_PERSONALIZE = (byte) 0x10;
    private static final byte INS_VERIFY_PIN = (byte) 0x20;
    private static final byte INS_STORE_FINGERPRINT = (byte) 0x30;
    private static final byte INS_VERIFY_FINGERPRINT = (byte) 0x31;
    private static final byte INS_GET_VOTER_ID = (byte) 0x40;
    private static final byte INS_CHECK_VOTE_STATUS = (byte) 0x50;
    private static final byte INS_SET_VOTED = (byte) 0x51;
    private static final byte INS_INIT_SECURE_CHANNEL = (byte) 0x60;
    private static final byte INS_ESTABLISH_SESSION = (byte) 0x61;
    private static final byte INS_GET_CHALLENGE = (byte) 0x70;
    private static final byte INS_GET_SIGNATURE = (byte) 0x71;
    private static final byte INS_GET_PUBLIC_KEY = (byte) 0x72;
    private static final byte INS_WRITE_VOTER_CREDENTIAL = (byte) 0x80;
    private static final byte INS_LOCK_CARD = (byte) 0x90;

    // ==================== STATUS WORDS ====================
    private static final short SW_PIN_VERIFICATION_REQUIRED = 0x6300;
    private static final short SW_PIN_BLOCKED = (short) 0x6983;
    private static final short SW_ALREADY_VOTED = (short) 0x6A81;
    private static final short SW_FINGERPRINT_NOT_MATCH = (short) 0x6A82;
    private static final short SW_SECURE_CHANNEL_NOT_ESTABLISHED = (short) 0x6982;
    private static final short SW_CONDITIONS_NOT_SATISFIED = (short) 0x6985;
    private static final short SW_CARD_LOCKED = (short) 0x6986;
    private static final short SW_NOT_PERSONALIZED = (short) 0x6987;

    // ==================== SIZES ====================
    private static final short VOTER_ID_SIZE = 32;
    private static final short FINGERPRINT_TEMPLATE_SIZE = 512;
    private static final short SESSION_KEY_SIZE = 16;
    private static final short CHALLENGE_SIZE = 16;
    private static final short PIN_HASH_SIZE = 32;
    private static final short STATIC_KEY_SIZE = 16;
    private static final short IV_SIZE = 16;
    private static final short ADMIN_TOKEN_SIZE = 32;
    private static final byte MAX_PIN_TRIES = (byte) 3;
    private static final short MATCH_THRESHOLD_PERCENT = (short) 80;
    private static final short ELECTION_ID_SIZE = 36;

    private static final byte[] SIGNED_MESSAGE = {
            'I', 'd', 'e', 'n', 't', 'i', 't', 'y', ' ', 'C', 'r', 'y', 'p', 't', 'o',
            'g', 'r', 'a', 'p', 'h', 'i', 'c', 'a', 'l', 'l', 'y', ' ', 'V', 'e', 'r', 'i', 'f', 'i', 'e', 'd'
    };

    // ==================== PERSISTENT EEPROM FIELDS ====================
    private byte[] voterID;
    private byte[] storedPINHash;
    private byte[] fingerprintTemplate;
    private byte[] cardStaticKey;
    private byte[] adminTokenHash;
    private byte[] lastElectionId;
    private boolean personalized;
    private boolean locked;
    private boolean fingerprintStored;
    private byte pinTriesRemaining;

    // ==================== SESSION (TRANSIENT) ====================
    private byte[] sessionKey;
    private byte[] currentChallenge;
    private byte[] adhocChallenge;
    private byte[] scratchPad;
    private byte[] ivBuffer;
    private boolean secureChannelEstablished;
    private boolean fingerprintVerifiedThisSession;
    private boolean pinValidatedThisSession;

    // ==================== CRYPTO ====================
    private AESKey aesKey;
    private Cipher aesCipher;
    private RandomData randomGenerator;
    private MessageDigest sha256;
    private Signature ecdsaSignature;
    private KeyPair ecKeyPair;

    // ==================== CONSTRUCTOR ====================
    private MFAVotingApplet() {
        voterID = new byte[VOTER_ID_SIZE];
        storedPINHash = new byte[PIN_HASH_SIZE];
        fingerprintTemplate = new byte[FINGERPRINT_TEMPLATE_SIZE];
        cardStaticKey = new byte[STATIC_KEY_SIZE];
        adminTokenHash = new byte[ADMIN_TOKEN_SIZE];
        lastElectionId = new byte[ELECTION_ID_SIZE];

        personalized = false;
        locked = false;
        fingerprintStored = false;
        pinTriesRemaining = MAX_PIN_TRIES;

        sessionKey = JCSystem.makeTransientByteArray(SESSION_KEY_SIZE, JCSystem.CLEAR_ON_DESELECT);
        currentChallenge = JCSystem.makeTransientByteArray(CHALLENGE_SIZE, JCSystem.CLEAR_ON_DESELECT);
        adhocChallenge = JCSystem.makeTransientByteArray(CHALLENGE_SIZE, JCSystem.CLEAR_ON_DESELECT);
        scratchPad = JCSystem.makeTransientByteArray((short) 512, JCSystem.CLEAR_ON_DESELECT);
        ivBuffer = JCSystem.makeTransientByteArray(IV_SIZE, JCSystem.CLEAR_ON_DESELECT);

        secureChannelEstablished = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession = false;

        try {
            aesKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
            aesCipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
            randomGenerator = RandomData.getInstance(RandomData.ALG_KEYGENERATION);
            sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
            ecKeyPair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            ecdsaSignature = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
        } catch (CryptoException e) {
            SystemException.throwIt(SystemException.NO_RESOURCE);
        }
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MFAVotingApplet().register(bArray, (short) (bOffset + 1), bArray[bOffset]);
    }

    public boolean select() {
        secureChannelEstablished = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession = false;
        if (ecdsaSignature != null && ecKeyPair != null && ecKeyPair.getPrivate().isInitialized()) {
            ecdsaSignature.init(ecKeyPair.getPrivate(), Signature.MODE_SIGN);
        }
        return true;
    }

    public void deselect() {
        Util.arrayFillNonAtomic(scratchPad, (short) 0, (short) scratchPad.length, (byte) 0);
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        secureChannelEstablished = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession = false;
    }

    // ==================== DISPATCH ====================
    public void process(APDU apdu) {
        if (selectingApplet())
            return;
        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != CLA_EVOTING)
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_PERSONALIZE:
                personalize(apdu);
                break;
            case INS_INIT_SECURE_CHANNEL:
                initSecureChannel(apdu);
                break;
            case INS_ESTABLISH_SESSION:
                establishSession(apdu);
                break;
            case INS_GET_CHALLENGE:
                getChallenge(apdu);
                break;
            case INS_VERIFY_PIN:
                verifyPIN(apdu);
                break;
            case INS_STORE_FINGERPRINT:
                storeFingerprint(apdu);
                break;
            case INS_VERIFY_FINGERPRINT:
                verifyFingerprint(apdu);
                break;
            case INS_GET_VOTER_ID:
                getVoterID(apdu);
                break;
            case INS_CHECK_VOTE_STATUS:
                checkVoteStatus(apdu);
                break;
            case INS_SET_VOTED:
                setVoted(apdu);
                break;
            case INS_GET_SIGNATURE:
                generateSignature(apdu);
                break;
            case INS_GET_PUBLIC_KEY:
                getPublicKey(apdu);
                break;
            case INS_WRITE_VOTER_CREDENTIAL:
                writeVoterCredential(apdu);
                break;
            case INS_LOCK_CARD:
                lockCard(apdu);
                break;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // ==================== AES HELPERS ====================
    private short aesCBCEncryptZeroIV(byte[] src, short srcOff, short len, byte[] dst, short dstOff) {
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        aesCipher.init(aesKey, Cipher.MODE_ENCRYPT, ivBuffer, (short) 0, IV_SIZE);
        return aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    private void aesCBCDecryptZeroIV(byte[] src, short srcOff, short len, byte[] dst, short dstOff) {
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        aesCipher.init(aesKey, Cipher.MODE_DECRYPT, ivBuffer, (short) 0, IV_SIZE);
        aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    // ==================== PERSONALIZATION ====================
    private void personalize(APDU apdu) {
        if (personalized || locked)
            ISOException.throwIt(SW_CARD_LOCKED);
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();

        final short EXPECTED = (short) (STATIC_KEY_SIZE + 4 + VOTER_ID_SIZE
                + FINGERPRINT_TEMPLATE_SIZE + ADMIN_TOKEN_SIZE);
        if (lc != EXPECTED)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short off = ISO7816.OFFSET_CDATA;
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
    }

    // ==================== SECURE CHANNEL ====================
    private void initSecureChannel(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        randomGenerator.nextBytes(currentChallenge, (short) 0, CHALLENGE_SIZE);

        sha256.reset();
        sha256.update(buffer, ISO7816.OFFSET_CDATA, CHALLENGE_SIZE);
        sha256.update(currentChallenge, (short) 0, CHALLENGE_SIZE);
        sha256.doFinal(cardStaticKey, (short) 0, STATIC_KEY_SIZE, scratchPad, (short) 0);
        Util.arrayCopy(scratchPad, (short) 0, sessionKey, (short) 0, SESSION_KEY_SIZE);
        aesKey.setKey(sessionKey, (short) 0);

        aesCBCEncryptZeroIV(currentChallenge, (short) 0, CHALLENGE_SIZE, scratchPad, (short) 0);

        Util.arrayCopy(currentChallenge, (short) 0, buffer, (short) 0, CHALLENGE_SIZE);
        Util.arrayCopy(scratchPad, (short) 0, buffer, CHALLENGE_SIZE, CHALLENGE_SIZE);
        apdu.setOutgoingAndSend((short) 0, (short) (CHALLENGE_SIZE * 2));
    }

    private void establishSession(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        for (short i = 0; i < CHALLENGE_SIZE; i++) {
            scratchPad[i] = (byte) (buffer[(short) (ISO7816.OFFSET_CDATA + i)] ^ currentChallenge[i]);
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
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!personalized)
            ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (pinTriesRemaining == 0)
            ISOException.throwIt(SW_PIN_BLOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != PIN_HASH_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, PIN_HASH_SIZE, scratchPad, (short) 0);

        if (Util.arrayCompare(scratchPad, (short) 0, storedPINHash, (short) 0, PIN_HASH_SIZE) == 0) {
            pinTriesRemaining = MAX_PIN_TRIES;
            pinValidatedThisSession = true;
        } else {
            if (pinTriesRemaining > 0)
                pinTriesRemaining--;
            ISOException.throwIt((short) (0x6300 | (pinTriesRemaining & 0x0F)));
        }
    }

    // ==================== FINGERPRINT STORAGE ====================
    private void storeFingerprint(APDU apdu) {
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)
            ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, fingerprintTemplate, (short) 0);
        fingerprintStored = true;
    }

    // ==================== MATCH-ON-CARD ====================
    private void verifyFingerprint(APDU apdu) {
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (!personalized)
            ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!fingerprintStored)
            ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);

        short totalBits = (short) (lc * 8);
        short differentBits = 0;

        for (short i = 0; i < lc; i++) {
            byte v = (byte) (scratchPad[i] ^ fingerprintTemplate[i]);
            if ((v & (byte) 0x01) != 0) differentBits++;
            if ((v & (byte) 0x02) != 0) differentBits++;
            if ((v & (byte) 0x04) != 0) differentBits++;
            if ((v & (byte) 0x08) != 0) differentBits++;
            if ((v & (byte) 0x10) != 0) differentBits++;
            if ((v & (byte) 0x20) != 0) differentBits++;
            if ((v & (byte) 0x40) != 0) differentBits++;
            if ((v & (byte) 0x80) != 0) differentBits++;
        }

        short matchingBits = (short) (totalBits - differentBits);
        short chunk = (short) (totalBits / 100);
        short matchPercent;

        if (chunk == 0) {
            matchPercent = (short) ((short) (matchingBits * 100) / totalBits);
        } else {
            matchPercent = (short) (matchingBits / chunk);
        }

        if (matchPercent >= MATCH_THRESHOLD_PERCENT) {
            fingerprintVerifiedThisSession = true;
        } else {
            ISOException.throwIt(SW_FINGERPRINT_NOT_MATCH);
        }
    }

    // ==================== VOTER ID ====================
    private void getVoterID(APDU apdu) {
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!personalized)
            ISOException.throwIt(SW_NOT_PERSONALIZED);
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopy(voterID, (short) 0, buffer, (short) 0, VOTER_ID_SIZE);
        apdu.setOutgoingAndSend((short) 0, VOTER_ID_SIZE);
    }

    // ==================== VOTE STATUS ====================
    private void checkVoteStatus(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();

        if (lc != ELECTION_ID_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if (Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA, lastElectionId, (short) 0, ELECTION_ID_SIZE) == 0) {
            buffer[0] = (byte) 0x01;
        } else {
            buffer[0] = (byte) 0x00;
        }

        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    // ==================== SET VOTED ====================
    private void setVoted(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();

        if (lc < (short) 38) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short uuidOffset = (short) ((ISO7816.OFFSET_CDATA + lc) - ELECTION_ID_SIZE);

        if (Util.arrayCompare(buffer, uuidOffset, lastElectionId, (short) 0, ELECTION_ID_SIZE) == 0) {
            ISOException.throwIt(SW_ALREADY_VOTED);
        }

        short sigLen = ecdsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);

        Util.arrayCopy(buffer, uuidOffset, lastElectionId, (short) 0, ELECTION_ID_SIZE);

        Util.arrayCopy(scratchPad, (short) 0, buffer, (short) 0, sigLen);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // ==================== AUTH SIGNATURE ====================
    private void generateSignature(APDU apdu) {
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)
            ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession)
            ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != 1 || buffer[ISO7816.OFFSET_CDATA] != (byte) 0x01)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);

        short sigLen = ecdsaSignature.sign(SIGNED_MESSAGE, (short) 0,
                (short) SIGNED_MESSAGE.length,
                buffer, (short) 0);
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

    // ==================== WRITE VOTER CREDENTIAL ====================
    private void writeVoterCredential(APDU apdu) {
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)
            ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, lc, fingerprintTemplate, (short) 0);
        fingerprintStored = true;
    }

    // ==================== LOCK CARD ====================
    private void lockCard(APDU apdu) {
        if (!personalized)
            ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!secureChannelEstablished)
            ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != ADMIN_TOKEN_SIZE)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesCBCDecryptZeroIV(buffer, ISO7816.OFFSET_CDATA, ADMIN_TOKEN_SIZE,
                scratchPad, (short) 0);

        sha256.reset();
        sha256.doFinal(scratchPad, (short) 0, ADMIN_TOKEN_SIZE,
                scratchPad, ADMIN_TOKEN_SIZE);

        if (Util.arrayCompare(scratchPad, ADMIN_TOKEN_SIZE,
                adminTokenHash, (short) 0,
                ADMIN_TOKEN_SIZE) != 0) {
            Util.arrayFillNonAtomic(scratchPad, (short) 0, (short) (ADMIN_TOKEN_SIZE * 2), (byte) 0);
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        Util.arrayFillNonAtomic(scratchPad, (short) 0, (short) (ADMIN_TOKEN_SIZE * 2), (byte) 0);
        locked = true;
    }
}