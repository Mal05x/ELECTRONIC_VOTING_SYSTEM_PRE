/**
 * webcrypto.js — Browser-side ECDSA P-256 key management
 *
 * All crypto operations use the browser's native Web Crypto API.
 * The private key is stored in localStorage as a JWK (JSON Web Key).
 * It never leaves the browser — only the public key is sent to the server.
 *
 * Key format sent to backend: Base64-encoded SubjectPublicKeyInfo (SPKI)
 * Signing payload:  UTF-8 bytes of the state change UUID string
 * Signature format: Base64-encoded DER ECDSA signature (SHA-256)
 */

const STORAGE_KEY = "evoting_admin_ecdsa_keypair";
const ALG = { name: "ECDSA", namedCurve: "P-256" };
const SIGN_ALG = { name: "ECDSA", hash: { name: "SHA-256" } };

/**
 * Generate a new ECDSA P-256 keypair and store it in localStorage.
 * Returns { publicKeyB64 } — the Base64 public key to send to the server.
 */
export async function generateAndStoreKeypair() {
 const keypair = await window.crypto.subtle.generateKey(ALG, false, ["sign","verify"]);
 // Store keypair.privateKey object in IndexedDB — it cannot be exported

  // Export private key as JWK for localStorage storage
  const privateJwk = await window.crypto.subtle.exportKey("jwk", keypair.privateKey);
  // Export public key as SPKI (SubjectPublicKeyInfo) — what the backend expects
  const publicSpki  = await window.crypto.subtle.exportKey("spki", keypair.publicKey);

  const publicB64 = btoa(String.fromCharCode(...new Uint8Array(publicSpki)));

  // Store keypair in localStorage
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    privateJwk,
    publicB64,
    createdAt: new Date().toISOString(),
  }));

  return { publicKeyB64: publicB64 };
}

/**
 * Check if a keypair exists in localStorage.
 */
export function hasStoredKeypair() {
  return localStorage.getItem(STORAGE_KEY) !== null;
}

/**
 * Sign a string payload (e.g. the state change UUID) with the stored private key.
 * Returns Base64-encoded DER ECDSA signature.
 */
export async function signPayload(payload) {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (!stored) throw new Error("No keypair found. Please register your key first.");

  const { privateJwk } = JSON.parse(stored);

  // Re-import private key from JWK
  const privateKey = await window.crypto.subtle.importKey(
    "jwk", privateJwk, ALG, false, ["sign"]
  );

  const encoder  = new TextEncoder();
  const data     = encoder.encode(payload);
  const sigBytes = await window.crypto.subtle.sign(SIGN_ALG, privateKey, data);

  return btoa(String.fromCharCode(...new Uint8Array(sigBytes)));
}

/**
 * Get the stored public key as Base64.
 */
export function getStoredPublicKey() {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (!stored) return null;
  return JSON.parse(stored).publicB64;
}

/**
 * Clear the stored keypair (e.g. on logout or key rotation).
 */
export function clearStoredKeypair() {
  localStorage.removeItem(STORAGE_KEY);
}
