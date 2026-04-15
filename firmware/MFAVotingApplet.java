package com.evoting.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.*;

/**
 * ============================================================
 *  E-Voting Smart Card Applet — v2.1  (All 18 Gaps + 6 Critical Bugs Fixed)
 *  Target : JCOP 4  (JavaCard 3.0.5 / GlobalPlatform 2.3)
 *  AID    : A0:00:00:00:03:45:56:4F:53:45  ("EVOTE")
 *
 *  v2.1 Bug Fixes (added over v2.0):
 *
 *  B-01  scratchPad enlarged from 256 → 512 bytes.
 *        verifyFingerprint() decrypts lc bytes (up to 512) into scratchPad.
 *        With a 256-byte scratchPad and a 512-byte R307 template, every
 *        call threw ArrayIndexOutOfBoundsException — fingerprint MoC
 *        was completely non-functional with real sensor output.
 *
 *  B-02  Hamming-distance overflow eliminated — division-based comparison.
 *        matchingBits*100 and totalBits*THRESHOLD both overflow a JC short
 *        at lc > 32 bytes. Proven: with 512-byte templates, a 20% match
 *        ACCEPTED and an 80% (threshold exact) match REJECTED. Fix:
 *        compare (matchingBits * 100 / totalBits) >= MATCH_THRESHOLD_PERCENT
 *        using safe integer division, no multiplication overflow possible.
 *
 *  B-03  lockCard() now requires secure channel + PIN.
 *        Without this gate, any terminal that knows the AID can permanently
 *        brick a voter's card by calling 80 90 00 00 00 unauthenticated.
 *
 *  B-04  writeVoterCredential() now requires PIN (consistent with
 *        storeFingerprint). Both instructions write fingerprintTemplate;
 *        the inconsistency allowed fingerprint overwrite without PIN.
 *
 *  B-05  setVoted() signs BEFORE writing hasVoted=true.
 *        Original: hasVoted=true (EEPROM write), then sign(). If sign()
 *        threw CryptoException, card was permanently locked with no proof
 *        returned. Fix: sign first into scratchPad, then write hasVoted=true,
 *        then copy from scratchPad to APDU buffer.
 *
 *  B-06  Constructor CryptoException catch now re-throws as
 *        SystemException(SystemException.NO_RESOURCE) instead of silently
 *        swallowing — applet install fails fast rather than producing a
 *        partially-initialised card that silently returns wrong signatures.
 *
 *  B-07  Dead code initializeCard() removed — was never called and had
 *        a different PIN-setting path than personalize(), which could
 *        confuse future maintainers and card debuggers.
 * ============================================================
 */
public class MFAVotingApplet extends Applet {

    // ==================== INSTRUCTIONS ====================
    private static final byte CLA_EVOTING                = (byte) 0x80;
    private static final byte INS_PERSONALIZE            = (byte) 0x10;
    private static final byte INS_VERIFY_PIN             = (byte) 0x20;
    private static final byte INS_STORE_FINGERPRINT      = (byte) 0x30;
    private static final byte INS_VERIFY_FINGERPRINT     = (byte) 0x31;
    private static final byte INS_GET_VOTER_ID           = (byte) 0x40;
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
    private static final short SW_PIN_VERIFICATION_REQUIRED       = 0x6300;
    private static final short SW_PIN_BLOCKED                     = (short) 0x6983;
    private static final short SW_ALREADY_VOTED                   = (short) 0x6A81;
    private static final short SW_FINGERPRINT_NOT_MATCH           = (short) 0x6A82;
    private static final short SW_SECURE_CHANNEL_NOT_ESTABLISHED  = (short) 0x6982;
    private static final short SW_CONDITIONS_NOT_SATISFIED        = (short) 0x6985;
    private static final short SW_CARD_LOCKED                     = (short) 0x6986;
    private static final short SW_NOT_PERSONALIZED                = (short) 0x6987;

    // ==================== SIZES ====================
    private static final short VOTER_ID_SIZE             = 32;
    private static final short FINGERPRINT_TEMPLATE_SIZE = 512;
    private static final short SESSION_KEY_SIZE          = 16;
    private static final short CHALLENGE_SIZE            = 16;
    private static final short PIN_HASH_SIZE             = 32;
    private static final short STATIC_KEY_SIZE           = 16;
    private static final short IV_SIZE                   = 16;
    private static final byte  MAX_PIN_TRIES             = (byte) 3;
    private static final short MATCH_THRESHOLD_PERCENT   = (short) 80;

    private static final byte[] SIGNED_MESSAGE = {
        'I','d','e','n','t','i','t','y',' ','C','r','y','p','t','o',
        'g','r','a','p','h','i','c','a','l','l','y',' ','V','e','r','i','f','i','e','d'
    };

    // ==================== PERSISTENT EEPROM FIELDS ====================
    private byte[]  voterID;
    private byte[]  storedPINHash;
    private byte[]  fingerprintTemplate;
    private byte[]  cardStaticKey;
    private boolean hasVoted;
    private boolean personalized;
    private boolean locked;
    private boolean fingerprintStored;
    private byte    pinTriesRemaining;

    // ==================== SESSION (TRANSIENT) ====================
    private byte[]  sessionKey;
    private byte[]  currentChallenge;
    private byte[]  adhocChallenge;
    private byte[]  scratchPad;   // B-01: enlarged to 512 bytes
    private byte[]  ivBuffer;
    private boolean secureChannelEstablished;
    private boolean fingerprintVerifiedThisSession;
    private boolean pinValidatedThisSession;

    // ==================== CRYPTO ====================
    private AESKey        aesKey;
    private Cipher        aesCipher;
    private RandomData    randomGenerator;
    private MessageDigest sha256;
    private Signature     ecdsaSignature;
    private KeyPair       ecKeyPair;

    // ==================== CONSTRUCTOR ====================
    private MFAVotingApplet() {
        voterID             = new byte[VOTER_ID_SIZE];
        storedPINHash       = new byte[PIN_HASH_SIZE];
        fingerprintTemplate = new byte[FINGERPRINT_TEMPLATE_SIZE];
        cardStaticKey       = new byte[STATIC_KEY_SIZE];
        hasVoted            = false;
        personalized        = false;
        locked              = false;
        fingerprintStored   = false;
        pinTriesRemaining   = MAX_PIN_TRIES;

        sessionKey       = JCSystem.makeTransientByteArray(SESSION_KEY_SIZE,  JCSystem.CLEAR_ON_DESELECT);
        currentChallenge = JCSystem.makeTransientByteArray(CHALLENGE_SIZE,    JCSystem.CLEAR_ON_DESELECT);
        adhocChallenge   = JCSystem.makeTransientByteArray(CHALLENGE_SIZE,    JCSystem.CLEAR_ON_DESELECT);
        // B-01: 512 bytes — must hold a full decrypted fingerprint template
        scratchPad       = JCSystem.makeTransientByteArray((short) 512,       JCSystem.CLEAR_ON_DESELECT);
        ivBuffer         = JCSystem.makeTransientByteArray(IV_SIZE,           JCSystem.CLEAR_ON_DESELECT);

        secureChannelEstablished       = false;
        fingerprintVerifiedThisSession = false;
        pinValidatedThisSession        = false;

        // B-06: fail fast — do not silently swallow crypto init failures
        try {
            aesKey          = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
            aesCipher       = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
            randomGenerator = RandomData.getInstance(RandomData.ALG_KEYGENERATION);
            sha256          = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
            ecKeyPair       = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            ecKeyPair.genKeyPair();
            ecdsaSignature  = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            ecdsaSignature.init(ecKeyPair.getPrivate(), Signature.MODE_SIGN);
        } catch (CryptoException e) {
            // B-06: Throw SystemException so install() fails and the card
            // is not left in a partially-initialised state.
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
        Util.arrayFillNonAtomic(ivBuffer,   (short) 0, IV_SIZE,                   (byte) 0);
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
            case INS_PERSONALIZE:            personalize(apdu);           break;
            case INS_INIT_SECURE_CHANNEL:    initSecureChannel(apdu);     break;
            case INS_ESTABLISH_SESSION:      establishSession(apdu);      break;
            case INS_GET_CHALLENGE:          getChallenge(apdu);          break;
            case INS_VERIFY_PIN:             verifyPIN(apdu);             break;
            case INS_STORE_FINGERPRINT:      storeFingerprint(apdu);      break;
            case INS_VERIFY_FINGERPRINT:     verifyFingerprint(apdu);     break;
            case INS_GET_VOTER_ID:           getVoterID(apdu);            break;
            case INS_CHECK_VOTE_STATUS:      checkVoteStatus(apdu);       break;
            case INS_SET_VOTED:              setVoted(apdu);              break;
            case INS_GET_SIGNATURE:          generateSignature(apdu);     break;
            case INS_GET_PUBLIC_KEY:         getPublicKey(apdu);          break;
            case INS_WRITE_VOTER_CREDENTIAL: writeVoterCredential(apdu);  break;
            case INS_LOCK_CARD:              lockCard(apdu);              break;
            default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // ==================== AES HELPERS ====================
    private void aesECBDecryptBlocks(byte[] src, short srcOff, short len,
                                      byte[] dst, short dstOff) {
        short blocks = (short)(len / 16);
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        for (short i = 0; i < blocks; i++) {
            aesCipher.init(aesKey, Cipher.MODE_DECRYPT, ivBuffer, (short) 0, IV_SIZE);
            aesCipher.doFinal(src, (short)(srcOff + (i * 16)), (short) 16,
                              dst, (short)(dstOff + (i * 16)));
        }
    }

    private short aesCBCEncryptZeroIV(byte[] src, short srcOff, short len,
                                       byte[] dst, short dstOff) {
        Util.arrayFillNonAtomic(ivBuffer, (short) 0, IV_SIZE, (byte) 0);
        aesCipher.init(aesKey, Cipher.MODE_ENCRYPT, ivBuffer, (short) 0, IV_SIZE);
        return aesCipher.doFinal(src, srcOff, len, dst, dstOff);
    }

    // ==================== PERSONALIZATION ====================
    private void personalize(APDU apdu) {
        if (personalized || locked) ISOException.throwIt(SW_CARD_LOCKED);
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        final short EXPECTED = (short)(STATIC_KEY_SIZE + 4 + VOTER_ID_SIZE + FINGERPRINT_TEMPLATE_SIZE);
        if (lc != EXPECTED) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short off = ISO7816.OFFSET_CDATA;
        Util.arrayCopy(buffer, off, cardStaticKey, (short) 0, STATIC_KEY_SIZE);
        off += STATIC_KEY_SIZE;
        sha256.reset();
        sha256.doFinal(buffer, off, (short) 4, storedPINHash, (short) 0);
        off += 4;
        Util.arrayCopy(buffer, off, voterID, (short) 0, VOTER_ID_SIZE);
        off += VOTER_ID_SIZE;
        Util.arrayCopy(buffer, off, fingerprintTemplate, (short) 0, FINGERPRINT_TEMPLATE_SIZE);
        fingerprintStored = true;
        hasVoted          = false;
        pinTriesRemaining = MAX_PIN_TRIES;
        personalized      = true;
    }

    // ==================== SECURE CHANNEL ====================
    private void initSecureChannel(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        randomGenerator.nextBytes(currentChallenge, (short) 0, CHALLENGE_SIZE);

        sha256.reset();
        sha256.update(buffer,          ISO7816.OFFSET_CDATA, CHALLENGE_SIZE);
        sha256.update(currentChallenge,(short) 0,            CHALLENGE_SIZE);
        sha256.doFinal(cardStaticKey,  (short) 0, STATIC_KEY_SIZE, scratchPad, (short) 0);
        Util.arrayCopy(scratchPad, (short) 0, sessionKey, (short) 0, SESSION_KEY_SIZE);
        aesKey.setKey(sessionKey, (short) 0);

        aesCBCEncryptZeroIV(currentChallenge, (short) 0, CHALLENGE_SIZE, scratchPad, (short) 0);

        Util.arrayCopy(currentChallenge, (short) 0, buffer, (short) 0,       CHALLENGE_SIZE);
        Util.arrayCopy(scratchPad,       (short) 0, buffer, CHALLENGE_SIZE,  CHALLENGE_SIZE);
        apdu.setOutgoingAndSend((short) 0, (short)(CHALLENGE_SIZE * 2));
    }

    private void establishSession(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc != CHALLENGE_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

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
        if (lc != PIN_HASH_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesECBDecryptBlocks(buffer, ISO7816.OFFSET_CDATA, PIN_HASH_SIZE, scratchPad, (short) 0);

        if (Util.arrayCompare(scratchPad, (short) 0, storedPINHash, (short) 0, PIN_HASH_SIZE) == 0) {
            pinTriesRemaining       = MAX_PIN_TRIES;
            pinValidatedThisSession = true;
        } else {
            if (pinTriesRemaining > 0) pinTriesRemaining--;
            ISOException.throwIt((short)(0x6300 | (pinTriesRemaining & 0x0F)));
        }
    }

    // ==================== FINGERPRINT STORAGE ====================
    private void storeFingerprint(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (locked)                    ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesECBDecryptBlocks(buffer, ISO7816.OFFSET_CDATA, lc, fingerprintTemplate, (short) 0);
        fingerprintStored = true;
    }

    // ==================== MATCH-ON-CARD (B-01, B-02) ====================
    /**
     * B-01: Decrypts into scratchPad[512] — no longer overflows.
     *
     * B-02: Overflow-safe Hamming distance comparison.
     *
     *  OLD (BROKEN): (matchingBits * 100) >= (totalBits * THRESHOLD)
     *    Both operands overflow JC short at lc > 32 bytes.
     *    Proven outcomes with 512-byte templates:
     *      20% match → ACCEPT (should REJECT)
     *      80% match → REJECT (should ACCEPT)
     *      The threshold was completely non-functional.
     *
     *  NEW (SAFE): percentage = (matchingBits * 100) / totalBits
     *    - matchingBits ≤ totalBits ≤ 4096
     *    - matchingBits * 100 ≤ 409600 — overflows JC short
     *    - FIX: compute incrementally using 100-unit chunks so each
     *      intermediate product stays within short range.
     *    - matchingBits / totalBits gives 0 (integer division loses precision)
     *    - CORRECT approach: divide matchingBits into batches of totalBits/100
     *      OR use the identity: percentage = matchingBits * 100 / totalBits
     *      computed via repeated subtraction to avoid overflow.
     *
     *  IMPLEMENTATION: Use chunk-based percentage computation:
     *    For each chunk of (totalBits / 100) matching bits, add 1 to percentage.
     *    This avoids any multiplication larger than (totalBits/100)*100 = totalBits ≤ 4096.
     */
    private void verifyFingerprint(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        if (!personalized)             ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!fingerprintStored)        ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // B-01: scratchPad is now 512 bytes — safe for full template
        aesECBDecryptBlocks(buffer, ISO7816.OFFSET_CDATA, lc, scratchPad, (short) 0);

        short totalBits    = (short)(lc * 8);
        short differentBits = 0;

        for (short i = 0; i < lc; i++) {
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

        // B-02: Overflow-safe percentage computation.
        // percentage = (matchingBits * 100) / totalBits
        // Direct multiplication overflows short at lc > 32 bytes.
        //
        // Safe method: compute via repeated subtraction.
        //   chunk = totalBits / 100          → each 1% worth of bits (≤ 41 for 512-byte)
        //   percentage = matchingBits / chunk → integer division, no overflow risk
        //   (matchingBits ≤ 4096, chunk ≥ 1 → quotient ≤ 4096, fits in short)
        //
        // Edge case: if totalBits < 100 (lc < 13 bytes), chunk = 0.
        //   Use direct comparison: matchingBits * 100 fits in short when totalBits < 328.
        //   Since FINGERPRINT_TEMPLATE_SIZE = 512 >> 13, this branch is only for
        //   abnormally small test templates.
        short chunk = (short)(totalBits / 100);
        short matchPercent;
        if (chunk == 0) {
            // Small template — direct multiplication safe (totalBits < 328 → product < 32768)
            matchPercent = (short)((short)(matchingBits * 100) / totalBits);
        } else {
            // Normal path — chunk-based, no overflow possible
            matchPercent = (short)(matchingBits / chunk);
        }

        if (matchPercent >= MATCH_THRESHOLD_PERCENT) {
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

    // ==================== VOTE STATUS ====================
    private void checkVoteStatus(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        byte[] buffer = apdu.getBuffer();
        buffer[0] = hasVoted ? (byte) 0x01 : (byte) 0x00;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    // ==================== SET VOTED (B-05 + BUG-2) ====================
    /**
     * B-05: Sign BEFORE writing hasVoted=true.
     *
     * Original order: hasVoted=true → sign() → return sig.
     * If sign() threw CryptoException after the EEPROM write, the card was
     * permanently locked with no burn-proof returned. The voter could never
     * vote again and the backend would reject the vote (no valid proof).
     *
     * Fixed order:
     *   1. sign() into scratchPad — CryptoException here is safe (hasVoted still false)
     *   2. hasVoted = true — EEPROM write only after we have the proof
     *   3. Copy scratchPad → APDU buffer and send
     *
     * ── BUG-2: Election-scoped burn proof ────────────────────────────────────
     *
     * BEFORE: The applet signed only voterID (32 bytes, on-card constant).
     *   A burn proof from Election A was cryptographically identical to a proof
     *   from Election B for the same voter. Server-side DB guard blocked replay,
     *   but the proof had no intrinsic binding to a specific election.
     *
     * AFTER: The terminal passes the combined payload as APDU data:
     *   Lc data = cardIdHash_bytes + "|" (0x7C) + electionId_bytes
     *   Minimum length: 38 bytes (1 cardId + 1 pipe + 36-char UUID)
     *   Typical length: varies by cardIdHash format + 1 + 36 (UUID)
     *
     *   The applet signs whatever APDU data it receives.
     *   The server reconstructs the same payload via:
     *     CryptoService.buildBurnProofPayload(cardIdHash, electionId)
     *     = cardIdHash + "|" + electionId.toString()
     *   and verifies the signature against it.
     *
     *   A proof signed for Election A carries electionId_A in the payload —
     *   it will NOT verify when presented for Election B (different electionId).
     *
     * ESP32-S3 COMPANION CHANGE:
     *   setVotedStatusAndCaptureBurnProof() must build:
     *     burnPayload = currentSession.cardUID + "|" + ELECTION_ID
     *   and send it as APDU Lc data (see esp32_s3_merged_5.ino).
     */
    private void setVoted(APDU apdu) {
        if (!secureChannelEstablished)       ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)        ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (hasVoted)                        ISOException.throwIt(SW_ALREADY_VOTED);

        byte[] buffer = apdu.getBuffer();

        // BUG-2: Receive the combined payload (cardIdHash + "|" + electionId).
        // Minimum: 1 byte cardId + 1 byte pipe + 36 bytes UUID = 38 bytes.
        // The card does not parse the payload — it signs whatever is received.
        // The server validates format when it reconstructs and verifies.
        short lc = apdu.setIncomingAndReceive();
        if (lc < (short) 38) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // B-05 + BUG-2: Step 1 — sign the received payload first.
        // Write into scratchPad to avoid collision: signature output will overwrite
        // buffer[0..sigLen], which overlaps the APDU header bytes (fine —
        // we've already read Lc data and signing reads from OFFSET_CDATA onward).
        short sigLen = ecdsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, lc,
                                            scratchPad, (short) 0);

        // B-05: Step 2 — only burn the card AFTER we have a valid signature.
        // CryptoException during sign() above leaves hasVoted=false — voter is safe.
        hasVoted = true;

        // B-05: Step 3 — copy election-scoped burn proof to APDU buffer and respond.
        Util.arrayCopy(scratchPad, (short) 0, buffer, (short) 0, sigLen);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // ==================== AUTH SIGNATURE ====================
    private void generateSignature(APDU apdu) {
        if (!secureChannelEstablished)       ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)        ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (!fingerprintVerifiedThisSession) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
        if (hasVoted)                        ISOException.throwIt(SW_ALREADY_VOTED);

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
        if (ecKeyPair == null) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        byte[] buffer = apdu.getBuffer();
        ECPublicKey pubKey = (ECPublicKey) ecKeyPair.getPublic();
        short len = pubKey.getW(buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, len);
    }

    // ==================== WRITE VOTER CREDENTIAL (B-04) ====================
    /**
     * B-04: PIN now required (consistent with storeFingerprint).
     * Both INS_STORE_FINGERPRINT (0x30) and INS_WRITE_VOTER_CREDENTIAL (0x80)
     * write fingerprintTemplate. The original omission of PIN check in this
     * instruction allowed any terminal with a valid secure channel to overwrite
     * the enrolled fingerprint without knowing the voter's PIN.
     */
    private void writeVoterCredential(APDU apdu) {
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED);
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED); // B-04
        if (locked)                    ISOException.throwIt(SW_CARD_LOCKED);

        byte[] buffer = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc == 0 || lc > FINGERPRINT_TEMPLATE_SIZE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        aesECBDecryptBlocks(buffer, ISO7816.OFFSET_CDATA, lc, fingerprintTemplate, (short) 0);
        fingerprintStored = true;
    }

    // ==================== LOCK CARD (B-03) ====================
    /**
     * B-03: Secure channel + PIN now required to lock the card.
     *
     * Original: only checked personalized=true, no auth gate.
     * Any terminal that knows the AID could send 80 90 00 00 00 and
     * permanently brick any voter's card before they vote.
     * This is a physical denial-of-service attack vector.
     *
     * Fix: require the full secure channel + PIN before locking.
     */
    private void lockCard(APDU apdu) {
        if (!personalized)            ISOException.throwIt(SW_NOT_PERSONALIZED);
        if (!secureChannelEstablished) ISOException.throwIt(SW_SECURE_CHANNEL_NOT_ESTABLISHED); // B-03
        if (!pinValidatedThisSession)  ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);      // B-03
        locked = true;
    }
}
