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
 * Idempotent — calling again with a new publicKey rotates the key.
 *
 * @param {object} data
 *   terminalId   {string}  — unique terminal identifier (matches firmware TERMINAL_ID)
 *   publicKey    {string}  — Base64 SPKI ECDSA P-256 public key from Serial Monitor
 *   label        {string}  — human-readable location description
 *   pollingUnitId {number} — optional integer ID from polling_units table
 */
export async function provisionTerminal(data) {
  const res = await client.post("/admin/terminals/provision", data);
  return res.data;
}

export async function deactivateTerminal(terminalId) {
  const res = await client.put(`/admin/terminals/${terminalId}/deactivate`);
  return res.data;
}
