/**
 * Elections API — AdminController /api/admin/elections
 *
 * Activation endpoints (verified against AdminController.java):
 *   PUT /api/admin/elections/{id}/activate  → ACTIVE  (no body)
 *   PUT /api/admin/elections/{id}/close     → CLOSED + bulk card unlock (no body)
 *
 * Candidates:
 *   GET  /api/admin/elections/{id}/candidates
 *   POST /api/admin/candidates  { electionId, fullName, party (abbreviation), position }
 *
 * Parties (needed for candidate creation):
 *   GET  /api/admin/parties
 *   POST /api/admin/parties  { name, abbreviation, foundedYear }
 */
import client from "./client.js";

export async function getElections() {
  const res = await client.get("/admin/elections");
  return Array.isArray(res.data) ? res.data : [];
}

export async function createElection(data, headers = {}) {
  const res = await client.post("/admin/elections", data, { headers });
  return res.data;
}

export async function setElectionStatus(id, status) {
  const ACTION = { ACTIVE: "activate", CLOSED: "close" };
  const action = ACTION[status];
  if (!action) throw new Error(`Unknown election status: ${status}`);
  const res = await client.put(`/admin/elections/${id}/${action}`);
  return res.data;
}

export async function getCandidates(electionId) {
  const res = await client.get(`/admin/elections/${electionId}/candidates`);
  return res.data;
}

export async function addCandidate(data) {
  // Backend: POST /api/admin/candidates (not /elections/{id}/candidates)
  const res = await client.post("/admin/candidates", data);
  return res.data;
}

export async function getParties() {
  const res = await client.get("/admin/parties");
  return res.data;
}

export async function createParty(data) {
  const res = await client.post("/admin/parties", data);
  return res.data;
}

/**
 * Initiate a multi-sig candidate removal request.
 * Returns { status, changeId, required } — status is "EXECUTED" (single-admin)
 * or "PENDING_APPROVAL" (multi-admin — requires co-signature in Approvals tab).
 *
 * Note: step-up auth headers are no longer needed here — the multi-sig flow
 * uses the ECDSA signature from signChallenge directly.
 * @param {string} candidateId  UUID of the candidate to remove
 * @param {string} [signature]  Optional ECDSA signature of the changeId (supplied by caller)
 */
export async function initiateRemoveCandidate(candidateId, signature = null) {
  const params = signature ? `?signature=${encodeURIComponent(signature)}` : "";
  const res = await client.post(`/admin/candidates/${candidateId}/remove${params}`);
  return res.data;
}

/** @deprecated Use initiateRemoveCandidate instead */
export async function deleteCandidate(candidateId, authHeaders = {}) {
  const res = await client.delete(`/admin/candidates/${candidateId}`, { headers: authHeaders });
  return res.data;
}
export const deleteElection = async (id) => {
  const res = await client.delete(`/admin/elections/${id}`);
  return res.data;
};

export const unlockCards = async (id) => {
  const res = await client.post(`/admin/elections/${id}/unlock-cards`);
  return res.data;
};

/**
 * POST /api/images/candidate/{candidateId}
 * Uploads a candidate photo to S3. Returns { imageUrl }.
 * Requires ADMIN or SUPER_ADMIN.
 */
export async function uploadCandidatePhoto(candidateId, file) {
  const form = new FormData();
  // Backend ImageController expects field name "image" (not "file")
  form.append("image", file);
  // DO NOT set Content-Type manually — axios/browser must set it automatically
  // so the multipart boundary is included (e.g. multipart/form-data; boundary=xxxx).
  // Manually setting Content-Type without the boundary breaks Spring's multipart parser.
  const res = await client.post(`/admin/images/candidate/${candidateId}`, form);
  return res.data; // { candidateId, imageUrl, s3Key }
}

export async function deleteCandidatePhoto(candidateId) {
  const res = await client.delete(`/admin/images/candidate/${candidateId}`);
  return res.data;
}
