/**
 * Authentication API
 *
 * POST /api/auth/login            → { token, role, username, email }
 * POST /api/auth/logout           → acknowledged
 * POST /api/auth/forgot-password  → { message }  (unauthenticated)
 * PUT  /api/auth/change-password  → { message }  (requires JWT)
 * GET  /api/admin/me              → { username, email, displayName, role, lastLogin }
 * PUT  /api/admin/me              → { username, email, displayName }
 */
import client from "./client.js";

export async function login(username, password) {
  const res = await client.post("/auth/login", { username, password });
  return res.data; // { token, role, username, email }
}

export async function logout() {
  try { await client.post("/auth/logout"); } catch (_) {}
}

export async function forgotPassword(email) {
  const res = await client.post("/auth/forgot-password", { email });
  return res.data;
}

export async function changePassword(currentPassword, newPassword) {
  const res = await client.put("/auth/change-password", { currentPassword, newPassword });
  return res.data;
}

/** Fetch the authenticated admin's full profile from the database */
export async function getProfile() {
  const res = await client.get("/admin/me");
  return res.data;
}

/** Update username/email/displayName in the database */
export async function updateProfile(fields) {
  const res = await client.put("/admin/me", fields);
  return res.data;
}

/** POST /api/auth/reset-password  { token, newPassword } */
export async function resetPassword(token, newPassword) {
  const res = await client.post("/auth/reset-password", { token, newPassword });
  return res.data;
}
