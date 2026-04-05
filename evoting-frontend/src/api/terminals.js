/**
 * Terminal Monitoring API
 * GET /api/admin/terminals                         → list all terminals (latest heartbeat per terminal)
 * PUT /api/admin/terminals/{terminalId}/resolve    → acknowledge tamper alert
 */
import client from "./client.js";

export async function getTerminals() {
  const res = await client.get("/admin/terminals");
  return Array.isArray(res.data) ? res.data : [];
}

export async function resolveTamperAlert(terminalId) {
  const res = await client.put(`/admin/terminals/${terminalId}/resolve`);
  return res.data;
}

/**
 * GET /api/admin/anomalies
 * Returns recent anomaly alerts (vote-rate spikes, suspicious patterns).
 */
export async function getAnomalyAlerts() {
  const res = await client.get("/admin/anomalies");
  return Array.isArray(res.data) ? res.data : [];
}
