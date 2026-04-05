/**
 * KeypairContext — manages the SUPER_ADMIN's ECDSA P-256 keypair.
 *
 * Private key: generated in browser via Web Crypto API, stored in
 * localStorage as a JWK (JSON Web Key). Never sent to the server.
 *
 * Public key: exported as SPKI → Base64 and sent to server via POST /api/admin/keypair.
 *
 * signChallenge(payload): signs a UTF-8 string using the stored private key.
 *   CRITICAL FORMAT NOTE:
 *   Web Crypto's sign() returns IEEE P1363 format (raw 64 bytes: r‖s, 32 bytes each).
 *   Java's SHA256withECDSA.verify() expects DER/ASN.1 format.
 *   These are INCOMPATIBLE. The p1363ToDer() converter below fixes this before
 *   Base64-encoding the signature. Without this, every signature fails verification.
 */
import { createContext, useContext, useState, useEffect, useCallback } from "react";
import { registerKeypair, getKeypairStatus } from "../api/multisig.js";

const KeypairContext = createContext({
  hasLocalKey:         false,
  hasServerKey:        false,
  keypairStatus:       null,
  generating:          false,
  error:               null,
  needsSetup:          true,
  generateAndRegister: async () => {},
  signChallenge:       async () => null,
  refreshStatus:       () => {},
});

const STORAGE_KEY = "evoting_admin_privkey";

// ─── P1363 → DER converter ────────────────────────────────────────────────────
/**
 * Converts a Web Crypto ECDSA P1363 signature (raw 64-byte r‖s) to
 * the DER/ASN.1 format that Java's SHA256withECDSA.verify() expects.
 *
 * P1363:  [ r (32 bytes) | s (32 bytes) ]
 * DER:    SEQUENCE { INTEGER r, INTEGER s }
 *         0x30 <len> 0x02 <rlen> <r> 0x02 <slen> <s>
 *
 * Each integer must have a leading 0x00 if the high bit is set (to keep it positive).
 */
function p1363ToDer(p1363Bytes) {
  if (p1363Bytes.length !== 64) {
    throw new Error(`Expected 64-byte P1363 signature, got ${p1363Bytes.length}`);
  }

  // Strip leading zeros but preserve at least one byte; prepend 0x00 if MSB set
  function encodeInt(bytes) {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start++;
    const trimmed = bytes.slice(start);
    // If high bit set, prepend 0x00 to indicate positive integer
    if (trimmed[0] & 0x80) {
      return new Uint8Array([0x00, ...trimmed]);
    }
    return trimmed;
  }

  const r = encodeInt(p1363Bytes.slice(0, 32));
  const s = encodeInt(p1363Bytes.slice(32, 64));

  // Build DER: 0x30 (SEQUENCE) + total length + 0x02 (INTEGER) + r + 0x02 + s
  const seqLen = 2 + r.length + 2 + s.length;
  const der = new Uint8Array(2 + seqLen);
  let i = 0;
  der[i++] = 0x30;           // SEQUENCE tag
  der[i++] = seqLen;         // SEQUENCE length
  der[i++] = 0x02;           // INTEGER tag for r
  der[i++] = r.length;       // r length
  der.set(r, i); i += r.length;
  der[i++] = 0x02;           // INTEGER tag for s
  der[i++] = s.length;       // s length
  der.set(s, i);

  return der;
}

// ─── Provider ─────────────────────────────────────────────────────────────────

export function KeypairProvider({ children }) {
  const [hasLocalKey,   setHasLocalKey]   = useState(false);
  const [hasServerKey,  setHasServerKey]  = useState(false);
  const [keypairStatus, setKeypairStatus] = useState(null);
  const [generating,    setGenerating]    = useState(false);
  const [error,         setError]         = useState(null);

  useEffect(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    setHasLocalKey(!!stored);

    getKeypairStatus()
      .then(s => { setKeypairStatus(s); setHasServerKey(s.hasKeypair); })
      .catch(() => {});
  }, []);

  /**
   * Generate a new ECDSA P-256 keypair, store the private key locally,
   * and register the public key (SPKI → Base64) on the server.
   */
  const generateAndRegister = useCallback(async () => {
    setGenerating(true);
    setError(null);
    try {
      const keypair = await window.crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,             // extractable — needed to export and store as JWK
        ["sign", "verify"]
      );

      // Store private key as JWK in localStorage
      const privJwk = await window.crypto.subtle.exportKey("jwk", keypair.privateKey);
      localStorage.setItem(STORAGE_KEY, JSON.stringify(privJwk));

      // Export public key as SPKI → Base64 for server
      const pubSpki   = await window.crypto.subtle.exportKey("spki", keypair.publicKey);
      const pubBase64 = btoa(String.fromCharCode(...new Uint8Array(pubSpki)));

      await registerKeypair(pubBase64);

      setHasLocalKey(true);
      setHasServerKey(true);

      const status = await getKeypairStatus();
      setKeypairStatus(status);

      return true;
    } catch (e) {
      setError(e.message || "Key generation failed");
      return false;
    } finally {
      setGenerating(false);
    }
  }, []);

  /**
   * Sign a string payload with the stored private key.
   *
   * Returns a Base64-encoded DER signature compatible with Java's
   * SHA256withECDSA.verify(). The P1363→DER conversion is applied
   * to the raw Web Crypto output before encoding.
   */
  const signChallenge = useCallback(async (payload) => {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return null;

    try {
      const privJwk = JSON.parse(stored);
      const privKey = await window.crypto.subtle.importKey(
        "jwk", privJwk,
        { name: "ECDSA", namedCurve: "P-256" },
        false, ["sign"]
      );

      const encoded = new TextEncoder().encode(payload);

      // sign() returns P1363 format (raw 64 bytes)
      const p1363Buf = await window.crypto.subtle.sign(
        { name: "ECDSA", hash: { name: "SHA-256" } },
        privKey, encoded
      );

      // Convert to DER format before base64-encoding
     // const derBytes = p1363ToDer(new Uint8Array(p1363Buf));
     // return btoa(String.fromCharCode(...derBytes));
     // NO CONVERSION NEEDED! Just Base64 encode the raw 64 bytes and send it to Java
           return btoa(String.fromCharCode(...new Uint8Array(p1363Buf)));

    } catch (e) {
      console.error("[KEYPAIR] Sign failed:", e);
      return null;
    }
  }, []);

  const refreshStatus = useCallback(() => {
    getKeypairStatus()
      .then(s => { setKeypairStatus(s); setHasServerKey(s.hasKeypair); })
      .catch(() => {});
  }, []);

  return (
    <KeypairContext.Provider value={{
      hasLocalKey, hasServerKey, keypairStatus,
      generating, error,
      generateAndRegister, signChallenge, refreshStatus,
      needsSetup: !hasLocalKey || !hasServerKey,
    }}>
      {children}
    </KeypairContext.Provider>
  );
}

export const useKeypair = () => useContext(KeypairContext);
