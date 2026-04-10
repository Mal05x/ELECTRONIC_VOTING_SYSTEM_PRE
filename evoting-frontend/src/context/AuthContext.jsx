import { createContext, useContext, useState, useCallback, useEffect } from "react";
import {
  login as apiLogin,
  logout as apiLogout,
  changePassword as apiChangePassword,
  getProfile,
  updateProfile as apiUpdateProfile,
} from "../api/auth.js";
import { clearStoredKeypair } from "../api/webcrypto.js";

const AuthContext = createContext(null);

/* ─── Avatar colours — one hex per letter A-Z ─── */
export const AVATAR_COLORS = {
  A:"#7C3AED", B:"#6D28D9", C:"#2563EB", D:"#0891B2",
  E:"#059669", F:"#D97706", G:"#DC2626", H:"#DB2777",
  I:"#9333EA", J:"#0284C7", K:"#16A34A", L:"#CA8A04",
  M:"#8B5CF6", N:"#7C3AED", O:"#4F46E5", P:"#0F766E",
  Q:"#A21CAF", R:"#B45309", S:"#1D4ED8", T:"#0369A1",
  U:"#15803D", V:"#B91C1C", W:"#C2410C", X:"#7C3AED",
  Y:"#0D9488", Z:"#6D28D9",
};

export function getAvatarColor(username = "") {
  const letter = (username[0] || "A").toUpperCase();
  return AVATAR_COLORS[letter] || "#8B5CF6";
}

export function getGreeting(username = "") {
  const first = username.split(/[_\s.]/)[0];
  const cap   = first.charAt(0).toUpperCase() + first.slice(1).toLowerCase();
  const h     = new Date().getHours();
  const greet = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
  return `${greet}, ${cap} 👋`;
}

function store(remember) {
  return remember ? localStorage : sessionStorage;
}

export function AuthProvider({ children }) {
  // Re-trigger notification load after login
  const { refreshNotifications } = useNotifications?.() || {};
  const [user,     setUser]     = useState(() => {
    try {
      const ls = localStorage.getItem("evoting_user");
      const ss = sessionStorage.getItem("evoting_user");
      return (ls || ss) ? JSON.parse(ls || ss) : null;
    } catch { return null; }
  });
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState("");
  const [pwStatus,        setPwStatus]        = useState(null);
  const [sessionWarning,  setSessionWarning]  = useState(false); // shows 2-min warning banner

  /* ── On mount: refresh profile from DB if we have a valid token ── */
  useEffect(() => {
    const token = localStorage.getItem("evoting_jwt") ||
                  sessionStorage.getItem("evoting_jwt");
    if (!token || !user) return;
    getProfile()
      .then(profile => {
        setUser(u => {
          const updated = { ...u, ...profile };
          const remember = localStorage.getItem("evoting_remember") === "true";
          store(remember).setItem("evoting_user", JSON.stringify(updated));
          return updated;
        });
      })
      .catch(() => {}); // silently — stale token will be caught by interceptor
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /* ── Session expiry warning — 2 minutes before JWT expires ── */
  useEffect(() => {
    if (!user) { setSessionWarning(false); return; }
    const token = localStorage.getItem("evoting_jwt") ||
                  sessionStorage.getItem("evoting_jwt");
    if (!token) return;
    try {
      const payload = JSON.parse(atob(token.split(".")[1]));
      const expiresAt = payload.exp * 1000; // convert to ms
      const warnAt    = expiresAt - 2 * 60 * 1000; // 2 minutes before
      const now       = Date.now();
      if (now >= expiresAt) return; // already expired, interceptor will handle it
      const warnDelay   = Math.max(0, warnAt - now);
      const expireDelay = Math.max(0, expiresAt - now);
      const warnTimer   = setTimeout(() => setSessionWarning(true),  warnDelay);
      const expireTimer = setTimeout(() => setSessionWarning(false), expireDelay);
      return () => { clearTimeout(warnTimer); clearTimeout(expireTimer); };
    } catch (_) {} // malformed token — interceptor will catch 401
  }, [user]);

  const login = useCallback(async (username, password, remember = false) => {
    setLoading(true); setError("");
    try {
      const data = await apiLogin(username, password);
      // Store JWT first so getProfile() can use it
      store(remember).setItem("evoting_jwt", data.token);
      localStorage.setItem("evoting_remember", String(remember));
      if (remember) localStorage.setItem("evoting_saved_user", username);

      // Immediately fetch full profile — login endpoint only returns
      // username/role/email, not displayName. Without this, displayName
      // is undefined until the user refreshes the page.
      let profile = {};
      try { profile = await getProfile(); } catch (_) {}

      const u = {
        username:    profile.username    || data.username || username,
        role:        profile.role        || data.role,
        email:       profile.email       || data.email || "",
        displayName: profile.displayName || "",
        lastLogin:   profile.lastLogin   || null,
        remember,
      };
      store(remember).setItem("evoting_user", JSON.stringify(u));
      setUser(u);
      // Trigger notification history + WebSocket reconnect now that JWT is stored
      try { refreshNotifications?.(); } catch (_) {}
      return true;
    } catch (err) {
      // Extract the most useful message from any Spring error shape:
      //   { "error": "..." }          — our GlobalExceptionHandler
      //   { "message": "..." }        — Spring Security default
      //   { "error": "...", "message": "..." } — Spring Boot error page
      const data = err.response?.data;
      const msg  = data?.error || data?.message || err.message || "Login failed";
      setError(msg);
      return false;
    } finally { setLoading(false); }
  }, []);

  const logout = useCallback(async () => {
    // Clear the ECDSA private key from localStorage on logout —
    // prevents key use after session ends if browser is left open
    try { await apiLogout(); } catch (_) {}
    localStorage.removeItem("evoting_jwt");
    localStorage.removeItem("evoting_user");
    try { clearStoredKeypair(); } catch (_) {}
    localStorage.removeItem("evoting_remember");
    sessionStorage.removeItem("evoting_jwt");
    sessionStorage.removeItem("evoting_user");
    setUser(null);
  }, []);

  const changePassword = useCallback(async (currentPassword, newPassword) => {
    setPwStatus(null);
    try {
      await apiChangePassword(currentPassword, newPassword);
      setPwStatus("success");
      return true;
    } catch (err) {
      setPwStatus("error:" + (err.response?.data?.error || err.message));
      return false;
    }
  }, []);

  /** Save profile fields to the DB, then update local state */
  const updateProfile = useCallback(async (fields) => {
    try {
      const saved = await apiUpdateProfile(fields);
      // Re-fetch full profile from server to ensure context is fully in sync
      const fresh = await import("../api/auth.js").then(m => m.getProfile()).catch(() => saved);
      setUser(u => {
        const updated = { ...u, ...fresh };
        const remember = localStorage.getItem("evoting_remember") === "true";
        store(remember).setItem("evoting_user", JSON.stringify(updated));
        return updated;
      });
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err.response?.data?.error || err.message };
    }
  }, []);

  // Alias for backwards compatibility with existing SettingsView calls
  const updateUser = updateProfile;


  const keypairNeeded = user?.role === "SUPER_ADMIN" && !localStorage.getItem("evoting_admin_ecdsa_keypair");

  return (
    <AuthContext.Provider value={{
      user, login, logout, loading, error, keypairNeeded,
      sessionWarning, setSessionWarning,
      changePassword, pwStatus, setPwStatus,
      updateProfile, updateUser,
      getAvatarColor, getGreeting,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
