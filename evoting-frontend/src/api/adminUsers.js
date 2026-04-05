/**
 * Admin User Management API
 * All endpoints require SUPER_ADMIN role.
 *
 * GET  /api/admin/users              → list all admin accounts
 * POST /api/admin/users              → create new admin
 * PUT  /api/admin/users/{id}/role    → change role
 * PUT  /api/admin/users/{id}/deactivate
 * PUT  /api/admin/users/{id}/activate
 */
import client from "./client.js";

export async function getAdminUsers() {
  const res = await client.get("/admin/users");
  return Array.isArray(res.data) ? res.data : [];
}

export async function createAdminUser({ username, password, role }) {
  const res = await client.post("/admin/users", { username, password, role });
  return res.data;
}

export async function changeAdminRole(id, role) {
  const res = await client.put(`/admin/users/${id}/role`, { role });
  return res.data;
}

export async function deactivateAdmin(id) {
  const res = await client.put(`/admin/users/${id}/deactivate`);
  return res.data;
}

export async function activateAdmin(id) {
  const res = await client.put(`/admin/users/${id}/activate`);
  return res.data;
}
