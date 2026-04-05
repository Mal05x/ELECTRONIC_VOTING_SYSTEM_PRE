/**
 * Tally / Results API — maps to Spring Boot TallyController
 *
 * Endpoints (all public — no JWT required):
 *   GET /api/results/{electionId}              → national tally + candidates
 *   GET /api/results/{electionId}/by-region    → adaptive regional breakdown
 *                                                 (STATE / LGA / POLLING_UNIT based on election type)
 *   GET /api/results/{electionId}/by-state     → full per-state breakdown (kept for compatibility)
 *   GET /api/results/{electionId}/state/{id}   → single state drill-down
 *   GET /api/receipt/{transactionId}           → vote receipt verifier
 */
import client from "./client.js";

export async function getNationalTally(electionId) {
  const res = await client.get(`/results/${electionId}`);
  return res.data;
}

/**
 * Adaptive regional breakdown.
 * Returns RegionalBreakdownDTO[] — regionType tells the UI which label to show:
 *   "STATE"        → Presidential elections
 *   "LGA"          → Gubernatorial / Senatorial / State Assembly
 *   "POLLING_UNIT" → Local Government elections
 *
 * Each row includes candidateTally (Map<candidateId, voteCount>)
 * so the drill-down panel can be built without a second API call.
 */
export async function getRegionalBreakdown(electionId) {
  const res = await client.get(`/results/${electionId}/by-region`);
  return Array.isArray(res.data) ? res.data : [];
}

/** Legacy — kept for any code still referencing it. Use getRegionalBreakdown() instead. */
export async function getStateTally(electionId) {
  const res = await client.get(`/results/${electionId}/by-state`);
  return res.data;
}

/** Single-state drill-down (stateId is an Integer, not UUID) */
export async function getSingleStateTally(electionId, stateId) {
  const res = await client.get(`/results/${electionId}/state/${stateId}`);
  return res.data;
}

export async function getReceipt(transactionId) {
  const res = await client.get(`/receipt/${transactionId}`);
  return res.data;
}
