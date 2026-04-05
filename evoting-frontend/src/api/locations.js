/**
 * locations.js — Location cascade API
 *
 * Maps directly to the backend controllers. No mock data, no fallbacks.
 * Errors propagate to the caller so the UI can surface them honestly.
 *
 * Public endpoints (no JWT):
 *   GET /api/locations/states
 *   GET /api/locations/states/{stateId}/lgas
 *   GET /api/locations/lgas/{lgaId}/polling-units
 *
 * Protected endpoints (JWT required):
 *   GET /api/admin/polling-units/lga/{lgaId}?page&size
 *   POST /api/admin/polling-units
 *
 * DTO shapes:
 *   StateDTO       : { id: Integer, name: String, code: String }
 *   LgaDTO         : { id: Integer, name: String, stateId: Integer, stateName: String }
 *   PollingUnitDTO : { id: Long, name: String, code: String,
 *                      lgaId: Integer, lgaName: String,
 *                      stateId: Integer, stateName: String,
 *                      capacity: Integer }
 */
import client from "./client.js";

/** All 37 states + FCT, sorted alphabetically by the backend. */
export async function getStates() {
  const res = await client.get("/locations/states");
  return Array.isArray(res.data) ? res.data : [];
}

/** All LGAs for a given state. */
export async function getLgasByState(stateId) {
  if (!stateId) return [];
  const res = await client.get(`/locations/states/${stateId}/lgas`);
  return Array.isArray(res.data) ? res.data : [];
}

/**
 * All polling units for a given LGA (public, no pagination).
 * Returns an empty array [] when the LGA exists but has no PUs seeded yet —
 * the caller is responsible for showing a "no PUs found" warning in that case.
 * Never falls back to generated/mock IDs that the backend would reject.
 */
export async function getPollingUnitsByLga(lgaId) {
  if (!lgaId) return [];
  const res = await client.get(`/locations/lgas/${lgaId}/polling-units`);
  // Handle both plain array and Spring Page object
  return Array.isArray(res.data) ? res.data : res.data?.content || [];
}

/**
 * JWT-protected paginated version — use in admin data tables.
 * Falls back to the public endpoint on 403 (e.g. OBSERVER role).
 */
export async function getPollingUnitsByLgaAdmin(lgaId, page = 0, size = 50) {
  if (!lgaId) return [];
  try {
    const res = await client.get(`/admin/polling-units/lga/${lgaId}`, {
      params: { page, size },
    });
    const d = res.data;
    return Array.isArray(d) ? d : d?.content || [];
  } catch (e) {
    if (e.response?.status === 403) return getPollingUnitsByLga(lgaId);
    throw e;
  }
}

/** Creates a new polling unit. Requires ADMIN or SUPER_ADMIN role. */
export async function createPollingUnit(data) {
  const res = await client.post("/admin/polling-units", data);
  return res.data;
}
