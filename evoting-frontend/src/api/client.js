/**
 * Axios client — Spring Boot backend
 *
 * HTTPS / CORS setup:
 * Dev:  Leave VITE_API_URL empty in .env.local — Vite proxy routes /api/* to
 * https://localhost:8443, handling the self-signed cert transparently.
 * Prod: Set VITE_API_URL=[https://your-backend.com](https://your-backend.com) (valid TLS cert required).
 * Set CORS_ALLOWED_ORIGIN=[https://your-frontend.vercel.app](https://your-frontend.vercel.app) on the backend.
 */
import axios from "axios";

// FORCING the relative path to bypass any cached .env variables
const BASE = "/api";

const client = axios.create({
  baseURL:  BASE,
  timeout:  20_000,
  headers:  { "Content-Type": "application/json" },
  withCredentials: false,
});

/*
 * Auth endpoints that must never trigger the global redirect-to-login handler.
 */
const AUTH_PATHS = [
  "/auth/login",
  "/auth/logout",
  "/auth/forgot-password",
  "/auth/change-password",
];

/* ── Request interceptor: attach JWT ── */
client.interceptors.request.use(
  (config) => {
    const token =
      localStorage.getItem("evoting_jwt") ||
      sessionStorage.getItem("evoting_jwt");
    if (token) config.headers.Authorization = `Bearer ${token}`;
    if (import.meta.env.DEV) {
      console.debug(`[API] ${config.method?.toUpperCase()} ${BASE}${config.url}`);
    }
    return config;
  },
  (err) => Promise.reject(err)
);

/* ── Response interceptor ── */
client.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err.response?.status;
    const url    = err.config?.url || "";

    // Is this one of the auth endpoints? Never redirect on these.
    const isAuthEndpoint = AUTH_PATHS.some(p => url.includes(p));

    /* Network error (no response object at all) */
    if (!err.response) {
      if (import.meta.env.DEV) {
        console.error(
          "[API] No response received. Possible causes:\n" +
          "  1. Backend is not running at: " + BASE.replace("/api", "") + "\n" +
          "  2. Self-signed TLS cert — set VITE_API_URL= (empty) so the Vite proxy handles it\n" +
          "  3. CORS blocked — check CORS_ALLOWED_ORIGIN includes this frontend origin\n" +
          "Original error: " + err.message
        );
      }
      return Promise.reject(err);
    }

    /* * THE FIX: Only redirect to /login on 401 (token missing/expired).
     * We removed 403 so Step-Up authentication can properly show cryptography errors!
     */
    if (!isAuthEndpoint && status === 401) {
      localStorage.removeItem("evoting_jwt");
      localStorage.removeItem("evoting_user");
      localStorage.removeItem("evoting_remember");
      sessionStorage.removeItem("evoting_jwt");
      sessionStorage.removeItem("evoting_user");
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }

    return Promise.reject(err);
  }
);

export default client;