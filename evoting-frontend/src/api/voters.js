/**
 * Voter Registry API
 *
 * Correct endpoints (verified against AdminController.java + CardManagementController.java):
 *
 *   GET  /api/admin/voters?electionId={uuid}&page=0&size=50  → paginated voters
 *   POST /api/admin/voters/register                          → register voter
 *   POST /api/admin/cards/lock   { cardIdHash, electionId }  → lock card
 *   POST /api/admin/cards/unlock { cardIdHash, electionId }  → unlock card
 *   GET  /api/admin/cards/history/{cardIdHash}               → card history
 *
 * NOTE: electionId is REQUIRED by the backend for listVoters.
 * VotersView must always pass an electionId.
 */
import client from "./client.js";

/**
 * electionId is required (UUID string).
 * Returns Page<VoterRegistry> — unwrapped to array here.
 */
export async function getVoters(electionId, page = 0, size = 50) {
  const res = await client.get("/admin/voters", {
    params: { electionId, page, size },
  });
  const data = res.data;
  return Array.isArray(data) ? data : data?.content ?? [];
}

export async function registerVoter(data) {
  const res = await client.post("/admin/voters/register", data);
  return res.data;
}

/**
 * Lock or unlock a card.
 * The backend uses two separate POST endpoints (CardManagementController):
 *   POST /api/admin/cards/lock   { cardIdHash, electionId }
 *   POST /api/admin/cards/unlock { cardIdHash, electionId }
 */
export async function setCardLock(cardIdHash, electionId, lock) {
  const endpoint = lock ? "/admin/cards/lock" : "/admin/cards/unlock";
  const res = await client.post(endpoint, { cardIdHash, electionId });
  return res.data;
}

export async function getCardHistory(cardIdHash) {
  const res = await client.get(`/admin/cards/history/${cardIdHash}`);
  return res.data;
}

export async function bulkUnlockCards(electionId) {
  const res = await client.post(`/admin/cards/unlock-all/${electionId}`);
  return res.data;
}
