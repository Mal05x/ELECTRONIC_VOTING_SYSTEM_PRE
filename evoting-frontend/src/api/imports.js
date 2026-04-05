/**
 * Bulk Import API — ElectionImportController
 *
 * Backend contract:
 *   POST /api/admin/import/json
 *        Body: raw JSON array — each row must contain electionId
 *        [ { fullName, partyAbbreviation, position, electionId }, ... ]
 *
 *   POST /api/admin/import/csv    multipart: file (field="file") + electionId (query param)
 *   POST /api/admin/import/excel  multipart: file (field="file") + electionId (query param)
 *
 * All require ADMIN or SUPER_ADMIN role.
 */
import client from "./client.js";

/**
 * Import candidates from a JSON array.
 * The backend expects a raw array — NOT a wrapper object.
 * We inject electionId into each row before sending.
 */
export async function importCandidatesJson(electionId, candidates, authHeaders = {}) {
  // Inject electionId into every row — backend maps each row to CandidateImportRowDTO
  // which has electionId as a required @NotNull field.
  const rows = candidates.map(c => ({ ...c, electionId }));
  const res = await client.post("/admin/import/json", rows, {
   // headers: { "Content-Type": "application/json" },
   // Spread the authHeaders so the backend gets the signature!
       headers: { "Content-Type": "application/json", ...authHeaders },
  });
  return res.data;
}

/**
 * Import candidates from a CSV file.
 * electionId is sent as a query param (backend uses @RequestParam).
 */
export async function importCandidatesCsv(electionId, file, authHeaders = {}) {
  const form = new FormData();
  form.append("file", file);
  const res = await client.post(`/admin/import/csv?electionId=${electionId}`, form, {
    headers: { "Content-Type": "multipart/form-data", ...authHeaders },
  });
  return res.data;
}

/**
 * Import candidates from an Excel file.
 * electionId is sent as a query param (backend uses @RequestParam).
 */
export async function importCandidatesExcel(electionId, file, authHeaders = {}) {
  const form = new FormData();
  form.append("file", file);
  const res = await client.post(`/admin/import/excel?electionId=${electionId}`, form, {
    headers: { "Content-Type": "multipart/form-data", ...authHeaders },
  });
  return res.data;
}
/**
 * Import candidates from an Tsv file.
 * electionId is sent as a query param (backend uses @RequestParam).
 */
export async function importCandidatesTsv(electionId, file, authHeaders = {}) {
  const form = new FormData();
  form.append("file", file);
  const res = await client.post(`/admin/import/excel?electionId=${electionId}`, form, {
    headers: { "Content-Type": "multipart/form-data", ...authHeaders },
  });
  return res.data;
}
/**
 * Delete a candidate using Step-Up Authentication.
 */
export async function deleteCandidate(candidateId, authHeaders = {}) {
  const res = await client.delete(`/admin/candidates/${candidateId}`, {
    // Spread the cryptographic headers so Java accepts the request!
    headers: { ...authHeaders },
  });
  return res.data;
}