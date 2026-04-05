/**
 * Audit Log API
 * GET  /api/admin/audit-log?page=0&size=80
 * GET  /api/admin/audit-log/verify
 */
import client from "./client.js";

export async function getAuditLog(params = {}) {
  const res = await client.get("/admin/audit-log", {
    params: { page: 0, size: 80, ...params },
  });
  // Backend returns a Spring Page object: { content: [...], totalElements, ... }
  const data = res.data;
  return Array.isArray(data) ? data : data?.content ?? [];
}

export async function verifyChain() {
  const res = await client.get("/admin/audit-log/verify");
  return res.data;
}
