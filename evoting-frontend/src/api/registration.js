/**
 * Registration API — two-step terminal-initiated voter registration
 */
import client from "./client.js";

// List pending cards awaiting demographics entry
export async function getPendingRegistrations() {
  const res = await client.get("/admin/voters/pending");
  return res.data;
}

// Admin commits demographics for a pending card
export async function commitRegistration(pendingId, data, authHeaders = {}) {
  const res = await client.post(`/admin/voters/commit-registration/${pendingId}`, data, { headers: authHeaders });
  return res.data;
}

// Cancel a pending registration
export async function cancelPendingRegistration(id) {
  const res = await client.delete(`/admin/voters/pending/${id}`);
  return res.data;
}

// Decrypt and view voter demographics — SUPER_ADMIN only, always audit logged
export async function getVoterDemographics(voterId) {
  const res = await client.get(`/admin/voters/${voterId}/demographics`);
  return res.data;
}
