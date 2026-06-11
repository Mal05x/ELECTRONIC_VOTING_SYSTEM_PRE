/**
 * Axios client — Spring Boot backend
 */
import axios from "axios";

const API_URL = import.meta.env.VITE_API_URL || "";
const BASE = API_URL ? `${API_URL}/api` : "/api";

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

// ── BUG-15 FIX: Global backend-offline state ──────────────────────────────────
// Tracks consecutive network failures. Components can subscribe via
// useBackendStatus() hook (see below) to show an offline banner.
let _consecutiveFailures = 0;
const _offlineListeners = new Set();
let _isOffline = false;

function _setOffline(offline) {
  if (_isOffline === offline) return;
  _isOffline = offline;
  _offlineListeners.forEach(fn => fn(offline));
}

export function onBackendStatusChange(fn) {
  _offlineListeners.add(fn);
  return () => _offlineListeners.delete(fn);
}

export function isBackendOffline() { return _isOffline; }

// ── BUG-12 FIX: Unique request ID per call ────────────────────────────────────
// Appended as X-Request-ID so server logs correlate with client logs,
// making EAGAIN retries and duplicate submissions easy to trace.
function generateRequestId() {
  return ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g, c =>
    (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16)
  );
}

/* ── Request interceptor: attach JWT + X-Request-ID ── */
client.interceptors.request.use(
  (config) => {
    const token =
      localStorage.getItem("evoting_jwt") ||
      sessionStorage.getItem("evoting_jwt");
    if (token) config.headers.Authorization = `Bearer ${token}`;

    // BUG-12 FIX: unique ID per request for server-side correlation
    config.headers["X-Request-ID"] = generateRequestId();

    if (import.meta.env.DEV) {
      console.debug(`[API] ${config.method?.toUpperCase()} ${BASE}${config.url} rid=${config.headers["X-Request-ID"]}`);
    }
    return config;
  },
  (err) => Promise.reject(err)
);

/* ── Response interceptor ── */
client.interceptors.response.use(
  (res) => {
    // BUG-15 FIX: clear offline state on any successful response
    _consecutiveFailures = 0;
    _setOffline(false);
    return res;
  },
  (err) => {
    const status = err.response?.status;
    const url    = err.config?.url || "";
    const isAuthEndpoint = AUTH_PATHS.some(p => url.includes(p));

    // BUG-15 FIX: count consecutive network/5xx failures → trigger offline banner
    if (!err.response || status >= 500) {
      _consecutiveFailures++;
      if (_consecutiveFailures >= 2) _setOffline(true);
    } else {
      _consecutiveFailures = 0;
      _setOffline(false);
    }

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