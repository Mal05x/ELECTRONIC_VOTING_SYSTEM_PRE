/**
 * Axios client — Spring Boot backend
 */
import axios from "axios";

// THE FIX:
// 1. In Production (Vercel): Uses the Render URL from your Vercel Environment Variables.
// 2. In Local Dev: Falls back to "/api" so your Vite proxy can handle the local SSL certificates.
const API_URL = import.meta.env.VITE_API_URL || "";
const BASE = API_URL ? `${API_URL}/api` : "/api";

const client = axios.create({
  baseURL:  BASE,
  timeout:  20_000,
  headers:  { "Content-Type": "application/json" },
  withCredentials: false, // Set to true ONLY if you are using cookies instead of LocalStorage for JWTs
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

    const isAuthEndpoint = AUTH_PATHS.some(p => url.includes(p));

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