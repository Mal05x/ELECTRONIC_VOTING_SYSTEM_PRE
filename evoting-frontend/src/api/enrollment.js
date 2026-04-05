/**
 * Enrollment Queue API — maps to Spring Boot AdminController
 *
 * The backend exposes the queue endpoint at one of these paths depending on
 * the version deployed. tryPost/tryGet probe them in order to avoid 404s.
 */
import client from "./client.js";

// 1. ADDED HEADERS: Now accepts the Step-Up auth signatures
async function tryPost(paths, body, headers = {}) {
  for (const path of paths) {
    try {
      // Pass the headers object into the Axios config
      const res = await client.post(path, body, { headers });
      return res.data;
    } catch (e) {
      if (e.response?.status === 404) continue;
      throw e;
    }
  }
  throw new Error("Enrollment queue endpoint not found (tried all paths)");
}

// 2. ADDED HEADERS: Just in case you ever need step-up for GET requests
async function tryGet(paths, params = {}, headers = {}) {
  for (const path of paths) {
    try {
      const res = await client.get(path, { params, headers });
      return res.data;
    } catch (e) {
      if (e.response?.status === 404) continue;
      throw e;
    }
  }
  throw new Error("Enrollment queue fetch endpoint not found (tried all paths)");
}

/**
 * POST /api/admin/enrollment/queue
 * Body: { terminalId, pollingUnitId (Long), electionId (UUID),
 *         voterPublicKey, encryptedDemographic }
 */
// 3. ADDED HEADERS: Accept headers from the React component
export async function queueEnrollment(data, headers = {}) {
  const body = {
    terminalId:           data.terminalId,
    pollingUnitId:        parseInt(data.pollingUnitId || data.pollingUnit || 0, 10),
    electionId:           data.electionId,
    voterPublicKey:       data.voterPublicKey       || "PENDING_FROM_TERMINAL",
    encryptedDemographic: data.encryptedDemographic || "PENDING_BIOMETRIC_CAPTURE",
  };

  // 4. Pass the headers down into tryPost
  return tryPost([
    "/admin/enrollment/queue",
    "/admin/enrollments",
    "/admin/enrollment",
  ], body, headers);
}

/** GET pending enrollments */
export async function getEnrollmentQueue(electionId, headers = {}) {
  // 1. Build the params object (only add electionId if it exists)
  const params = electionId ? { electionId } : {};

  // 2. Pass the 'params' variable directly
  return tryGet([
    "/admin/enrollment/pending",
    "/admin/enrollment/queue",
    "/admin/enrollments/queue",
  ], params, headers);
}

/** DELETE /api/admin/enrollment/queue/{id} */
// Added headers support here too, in case Canceling requires Step-Up Auth!
export async function cancelEnrollment(id, headers = {}) {
  const res = await client.delete(`/admin/enrollment/queue/${id}`, { headers });
  return res.data;
}