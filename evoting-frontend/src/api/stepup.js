/**
 * Step-Up Authentication API
 *
 * GET /api/auth/challenge?action=IMPORT_CANDIDATES
 *   Returns: { nonce, actionType, expiresIn, sigPayload }
 *
 * After getting a challenge, the frontend signs sigPayload
 * with the admin's ECDSA private key (via KeypairContext.signChallenge)
 * and includes the headers on the actual request:
 *   X-Action-Nonce:     <nonce>
 *   X-Action-Signature: <base64 ECDSA signature>
 */
import client from "./client.js";

export async function requestChallenge(action) {
  const res = await client.get("/auth/challenge", { params: { action } });
  return res.data;
}
