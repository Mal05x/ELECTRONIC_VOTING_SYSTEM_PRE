/**
 * Multi-Signature API
 * All endpoints require SUPER_ADMIN role + registered ECDSA keypair.
 */
import client from "./client.js";

// ── Keypair management ────────────────────────────────────────
export async function registerKeypair(publicKey) {
  const res = await client.post("/admin/keypair", { publicKey });
  return res.data;
}

export async function getKeypairStatus() {
  const res = await client.get("/admin/keypair/status");
  return res.data;
}

// ── State changes ─────────────────────────────────────────────
/**
 * Step 1 — Create a pending state change. Returns { changeId, pending, status }.
 * No signature needed here — sign the changeId separately using signStateChange().
 */
export async function initiateStateChange(action, targetId) {
  const res = await client.post(`/admin/state-changes/${action}/${targetId}`);
  return res.data;
}

export async function getPendingStateChanges() {
  const res = await client.get("/admin/state-changes");
  return res.data;
}

export async function signStateChange(changeId, signature) {
  const res = await client.post(`/admin/state-changes/${changeId}/sign`, { signature });
  return res.data;
}

export async function cancelStateChange(changeId, reason) {
  const res = await client.post(`/admin/state-changes/${changeId}/cancel`, { reason });
  return res.data;
}

export async function getStateChangeStatus(changeId) {
  const res = await client.get(`/admin/state-changes/${changeId}/status`);
  return res.data;
}

// Alias for backward compatibility with ApprovalsView
export const getPendingChanges = getPendingStateChanges;
