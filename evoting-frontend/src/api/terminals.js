/**
 * Terminal Monitoring & Registry API
 *
 * Heartbeat monitoring:
 *   GET /api/admin/terminals                         → heartbeat list (live status)
 *   PUT /api/admin/terminals/{terminalId}/resolve    → acknowledge tamper alert
 *
 * Terminal registry (application-layer signing):
 *   GET  /api/admin/terminals/registry              → list all provisioned terminals
 *   POST /api/admin/terminals/provision             → register / rotate key for a terminal
 *
 * Officer PIN provisioning:
 *   PUT  /api/admin/terminals/{terminalId}/officer-pin → set / rotate officer PIN
 *
 * NOTE — Backend serialisation requirement:
 *   TerminalRegistry.officerPinHash must be annotated @JsonIgnore.
 *   The registry endpoint should instead return a computed boolean field:
 *     "pinProvisioned": officerPinHash != null
 *   This prevents the hash from being exposed to frontend sessions,
 *   since the frontend only needs to know whether a PIN is set, not its hash.
 */
import client from "./client.js";

// ── Heartbeat monitoring ──────────────────────────────────────────────────────

export async function getTerminals() {
  const res = await client.get("/admin/terminals");
  return Array.isArray(res.data) ? res.data : [];
}

export async function resolveTamperAlert(terminalId) {
  const res = await client.put(`/admin/terminals/${terminalId}/resolve`);
  return res.data;
}

export async function getAnomalyAlerts() {
  const res = await client.get("/admin/anomalies");
  return Array.isArray(res.data) ? res.data : [];
}

// ── Terminal registry (provisioning) ─────────────────────────────────────────

export async function getTerminalRegistry() {
  const res = await client.get("/admin/terminals/registry");
  return Array.isArray(res.data) ? res.data : [];
}

/**
 * Provision (or rotate key for) a terminal.
 */
export async function provisionTerminal(data) {
  const res = await client.post("/admin/terminals/provision", data);
  return res.data;
}

export async function deactivateTerminal(terminalId) {
  const res = await client.put(`/admin/terminals/${terminalId}/deactivate`);
  return res.data;
}

// ── Officer PIN provisioning ──────────────────────────────────────────────────

/**
 * setOfficerPin — Set or rotate the 6-digit polling officer PIN for a terminal.
 *
 * The plain PIN is sent to the backend which hashes it with SHA-256 and
 * stores the hash in terminal_registry.officer_pin_hash. The plain PIN is
 * never persisted by the backend. After calling this function, the admin
 * must communicate the plain PIN to the Returning Officer out-of-band.
 *
 * @param {string} terminalId  — must match firmware TERMINAL_ID
 * @param {string} pin         — exactly 6 numeric digits
 * @returns {Promise<object>}  — { terminalId, message }
 * @throws  {AxiosError}       — 400 if pin is not 6 digits, 404 if terminal unknown
 */
export async function setOfficerPin(terminalId, pin) {
  const res = await client.put(
    `/admin/terminals/${terminalId}/officer-pin`,
    { pin }
  );
  return res.data;
}
