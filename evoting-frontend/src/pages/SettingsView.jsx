import { useState, useEffect } from "react";
import { useAuth, getAvatarColor, AVATAR_COLORS } from "../context/AuthContext.jsx";
import { useTheme } from "../context/ThemeContext.jsx";
import { SectionHeader, Ic, Spinner } from "../components/ui.jsx";
import client from "../api/client.js";
import { initiateStateChange, signStateChange } from "../api/multisig.js";
import { useKeypair } from "../context/KeypairContext.jsx";
import { getElections } from "../api/elections.js";

/* ─── Small helpers ─── */
function Label({ children }) {
  return <label className="block text-xs font-semibold text-sub mb-1.5 uppercase tracking-wide">{children}</label>;
}
function Toggle({ checked, onChange, label, sub }) {
  return (
    <div className="flex items-center justify-between py-3 border-b border-border/40 last:border-0">
      <div>
        <div className="text-sm font-semibold text-ink">{label}</div>
        {sub && <div className="text-xs text-muted mt-0.5">{sub}</div>}
      </div>
      <button type="button" onClick={() => onChange(!checked)}
        className={`relative w-11 h-6 rounded-full transition-colors duration-200 flex-shrink-0
                    ${checked ? "bg-purple-500" : "bg-elevated border border-border"}`}>
        <div className={`absolute top-1 w-4 h-4 rounded-full bg-white shadow transition-transform duration-200
                         ${checked ? "translate-x-6" : "translate-x-1"}`} />
      </button>
    </div>
  );
}
function SaveRow({ saving, onSave, label = "Save Changes" }) {
  return (
    <div className="mt-8 pt-5 border-t border-border flex justify-end">
      <button type="button" onClick={onSave}
        className="btn btn-primary btn-md min-w-[140px] justify-center" disabled={saving}>
        {saving ? <Spinner s={16} /> : <><Ic n="save" s={14} c="#fff" /> {label}</>}
      </button>
    </div>
  );
}
function ToastBar({ msg, type, onClose }) {
  if (!msg) return null;
  const styles = {
    error:   "bg-card border-red-500/30 text-danger",
    warning: "bg-card border-yellow-500/30 text-yellow-300",
    success: "bg-card border-purple-500/30 text-purple-300",
  };
  const icons = { error: "warning", warning: "warning", success: "check" };
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                     rounded-2xl border shadow-card text-sm font-semibold animate-slide-in
                     ${styles[type] || styles.success}`}>
      <Ic n={icons[type] || "check"} s={15} />
      {msg}
      <button onClick={onClose} className="text-muted hover:text-sub ml-1"><Ic n="close" s={12} /></button>
    </div>
  );
}

const TABS = [
  { id:"profile",       icon:"voters",   label:"My Profile"       },
  { id:"security",      icon:"lock",     label:"Security & 2FA"   },
  { id:"notifications", icon:"bell",     label:"Notifications"    },
  { id:"display",       icon:"sun",      label:"Display"          },
  { id:"session",       icon:"shield",   label:"Session & Access" },
  { id:"terminals",     icon:"chip",     label:"Terminals"        },
  { id:"system",        icon:"database", label:"System"           },
  { id:"about",         icon:"info",     label:"About"            },
];

export default function SettingsView() {
  const {
    hasLocalKey, hasServerKey, needsSetup,
    generating, generateAndRegister, signChallenge,
  } = useKeypair();
  const { user, updateProfile, changePassword, pwStatus, setPwStatus } = useAuth();
  const { theme, setTheme } = useTheme();

  const [activeTab, setActiveTab] = useState("profile");
  const [saving,    setSaving]    = useState(false);
  const [toast,     setToast]     = useState({ msg: "", type: "success" });
  // ADD THIS LINE:
    const [confirmPublish, setConfirmPublish] = useState(false);
  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  /* ── Profile state ── */
  //const [username, setUsername] = useState(user?.username || "");
  const [username, setUsername] = useState(user?.displayName || user?.username || "");
  const [email,    setEmail]    = useState(user?.email    || "");

  /* ── Security state ── */
  const [curPw,       setCurPw]       = useState("");
  const [keyGenStep,  setKeyGenStep]  = useState("idle"); // idle | generating | done | error
  const [keyGenError, setKeyGenError] = useState("");
  const [newPw,       setNewPw]       = useState("");
  const [confPw,      setConfPw]      = useState("");
  const [pwCountdown, setPwCountdown] = useState(null);

  useEffect(() => {
    if (!pwStatus) return;
    if (pwStatus === "success") {
      let n = 5; setPwCountdown(n);
      const t = setInterval(() => {
        n -= 1;
        if (n <= 0) { clearInterval(t); window.location.href = "/login"; }
        else setPwCountdown(n);
      }, 1000);
      return () => clearInterval(t);
    } else if (pwStatus.startsWith("error:")) {
      showToast(pwStatus.replace("error:", ""), "error");
      if (setPwStatus) setPwStatus(null);
    }
  }, [pwStatus]);


  /* ── Load saved preferences from backend on mount ── */
  useEffect(() => {
    const sections = ["notifications", "display", "session", "terminals", "system"];
    sections.forEach(section => {
      client.get(`/admin/settings/${section}`)
        .then(res => {
          if (!res.data) return;
          const d = res.data;
          if (section === "notifications") setNotif(p => ({ ...p, ...d }));
          if (section === "display") {
            if (d.density)    setDensity(d.density);
            if (d.dateFormat) setDateFormat(d.dateFormat);
            if (d.language)   setLanguage(d.language);
            if (d.fontSize)   setFontSize(d.fontSize);
          }
          if (section === "session") {
            if (d.autoLogout   != null) setAutoLogout(String(d.autoLogout));
            if (d.dualApproval != null) setDualApproval(!!d.dualApproval);
            if (d.sessionLogs  != null) setSessionLogs(!!d.sessionLogs);
          }
          if (section === "terminals") {
            if (d.heartbeat     != null) setHeartbeat(String(d.heartbeat));
            if (d.offlineThresh != null) setOfflineThresh(String(d.offlineThresh));
            if (d.autoLock      != null) setAutoLock(!!d.autoLock);
          }
          if (section === "system") {
            if (d.auditRetention   != null) setAuditRetention(String(d.auditRetention));
            if (d.backupSchedule)           setBackupSchedule(d.backupSchedule);
            if (d.merklePublish    != null) setMerklePublish(!!d.merklePublish);
            if (d.livenessFailOpen != null) setLivenessFailOpen(!!d.livenessFailOpen);
          }
        })
        .catch(() => {
          // Fallback to localStorage if endpoint returns 404/error
          const stored = localStorage.getItem(`evoting_pref_${section}`);
          if (stored) {
            try {
              const d = JSON.parse(stored);
              if (section === "display") {
                if (d.density)    setDensity(d.density);
                if (d.dateFormat) setDateFormat(d.dateFormat);
                if (d.language)   setLanguage(d.language);
                if (d.fontSize)   setFontSize(d.fontSize);
              }
            } catch (_) {}
          }
        });
    });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /* ── Notifications state ── */
  const [notif, setNotif] = useState({
    emailVoteCast: true, emailEnrollment: true,
    emailTamper: true, emailAuth: false, inAppAll: true, smsAlerts: false,
  });

  /* ── Display state ── */
  const [density,    setDensity]    = useState("comfortable");
  const [dateFormat, setDateFormat] = useState("dd/MM/yyyy");
  const [language,   setLanguage]   = useState("en-NG");
  const [fontSize,   setFontSize]   = useState("base");

  /* ── Session state ── */
  const [autoLogout,   setAutoLogout]   = useState("30");
  const [dualApproval, setDualApproval] = useState(false);
  const [sessionLogs,  setSessionLogs]  = useState(true);

  /* ── Terminals state ── */
  const [heartbeat,     setHeartbeat]     = useState("30");
  const [offlineThresh, setOfflineThresh] = useState("120");
  const [autoLock,      setAutoLock]      = useState(true);

  /* ── System state ── */
  const [auditRetention,  setAuditRetention]  = useState("365");
  const [backupSchedule,  setBackupSchedule]  = useState("daily");
  const [merklePublish,   setMerklePublish]   = useState(true);
  /**
   * Liveness fail-open: when false, the backend rejects votes if the
   * Python MiniFASNet service is unreachable — no AI evaluation, no vote.
   * When true, it falls back to basic JPEG validation (weaker, allows votes).
   * Changing this updates BiometricService at runtime via PUT /api/admin/system/liveness-config.
   */
  const [livenessFailOpen, setLivenessFailOpen] = useState(false); // default: strict (fail-open=false)

  /* ── System health (loaded on About tab) ── */
  const [health, setHealth] = useState(null);
  const [healthLoading, setHealthLoading] = useState(false);

  useEffect(() => {
    if (activeTab !== "about" || health) return;
    setHealthLoading(true);
    client.get("/admin/stats/overview")
      .then(res => setHealth(res.data))
      .catch(() => setHealth(null))
      .finally(() => setHealthLoading(false));
  }, [activeTab]);

  /* ── Save handlers — all call real backend endpoints ── */

  const saveProfile = async () => {
    setSaving(true);
    const result = await updateProfile({ email, displayName: username });
    setSaving(false);
    if (result?.ok === false) {
      showToast(result.error || "Failed to update profile", "error");
    } else {
      showToast("Profile updated successfully");
    }
  };

  const handleGenerateKey = async () => {
    setKeyGenStep("generating");
    setKeyGenError("");
    try {
      await generateAndRegister();
      setKeyGenStep("done");
      showToast("Signing key generated and registered successfully");
    } catch (e) {
      setKeyGenError(e.response?.data?.error || e.message || "Key generation failed");
      setKeyGenStep("error");
    }
  };

  const savePassword = async () => {
    if (!curPw || !newPw || !confPw) { showToast("All password fields are required", "error"); return; }
    if (newPw !== confPw)            { showToast("New passwords do not match", "error"); return; }
    if (newPw.length < 8)            { showToast("Password must be at least 8 characters", "error"); return; }
    setSaving(true);
    await changePassword(curPw, newPw);
    setSaving(false);
    // pwStatus effect handles countdown + redirect
  };

  /**
   * Generic save for preferences.
   * Sends to PUT /api/admin/settings/{section} as a flat JSON object.
   * If the backend doesn't have this endpoint yet it gracefully fails silently
   * and still saves to localStorage as a client-side preference.
   */
  const savePrefs = async (section, data, label) => {
    setSaving(true);
    try {
      await client.put(`/admin/settings/${section}`, data);
      showToast(`${label} saved`);
    } catch (e) {
      // Backend unavailable — persist locally and tell user
      localStorage.setItem(`evoting_pref_${section}`, JSON.stringify(data));
      const msg = e.response?.data?.error || e.message || "Server error";
      showToast(`Saved locally (${msg})`, "warning");
    }
    setSaving(false);
  };

  /* ── Manual actions ── */
 const publishMerkle = async () => {
     try {
       // Fetch active election to pass as electionId param
       const elections = await getElections().catch(() => []);
       const active = elections.find(e => e.status === "ACTIVE") || elections[0];

       if (!active) {
         showToast("No election found to publish Merkle root for", "error");
         return;
       }

       // FIX 1: Check the correct boolean flags from useKeypair
       if (!(hasLocalKey && hasServerKey)) {
         showToast("No signing key. Register keypair first.", "error");
         return;
       }

       const res = await initiateStateChange("PUBLISH_MERKLE_ROOT", active.id);

       if (!res.pending) {
         showToast("Merkle root published successfully");
       } else {
         // FIX 2: Use signChallenge from your hook instead of signPayload
         const sig = await signChallenge(res.changeId);
         if (sig) {
           const signed = await signStateChange(res.changeId, sig);
           if (signed.executed) {
             showToast("Merkle root published successfully");
             return;
           }
         }
         showToast("Merkle publish submitted — awaiting co-signature");
       }
     } catch (e) {
       showToast(e.response?.data?.error || "Merkle publish failed", "error");
     }
   };

  const exportAuditLog = async () => {
    try {
      const res = await client.get("/admin/audit-log?page=0&size=200", {
        responseType: "blob",
      });
      const url = URL.createObjectURL(new Blob([res.data]));
      const a = document.createElement("a");
      a.href = url; a.download = "audit-log.json"; a.click();
      URL.revokeObjectURL(url);
      showToast("Audit log exported");
    } catch (_) {
      // Fallback: open in new tab via normal fetch
      window.open(`${client.defaults.baseURL}/admin/audit-log?page=0&size=200`, "_blank");
    }
  };

  //const avatarLetter = (username[0] || user?.username?.[0] || "A").toUpperCase();
  //const avatarColor  = getAvatarColor(username || user?.username || "A");
  const avatarLetter = (username[0] || user?.displayName?.[0] || user?.username?.[0] || "A").toUpperCase();
  const avatarColor  = getAvatarColor(username || user?.displayName || user?.username || "A");

  const pwStrength = p => {
    let s = 0;
    if (p.length >= 8) s++;
    if (/[A-Z]/.test(p)) s++;
    if (/[0-9]/.test(p)) s++;
    if (/[^A-Za-z0-9]/.test(p)) s++;
    return s;
  };
  const strength      = pwStrength(newPw);
  const strengthColor = ["","#F87171","#FCD34D","#34D399","#A78BFA"][strength];
  const strengthLabel = ["","Weak","Fair","Good","Strong"][strength];

  return (
    <div className="p-7 flex flex-col gap-6 max-w-5xl mx-auto w-full">
      <ToastBar msg={toast.msg} type={toast.type} onClose={() => setToast({ msg: "", type: "success" })} />

      <div>
        <h1 className="text-2xl font-bold text-ink tracking-tight">System Settings</h1>
        <p className="text-sm text-sub mt-1">All changes are saved to the database immediately.</p>
      </div>

      <div className="flex flex-col md:flex-row gap-6">
        {/* Sidebar */}
        <div className="w-full md:w-56 flex-shrink-0 flex flex-col gap-0.5">
          {TABS.map(t => (
            <button key={t.id} onClick={() => setActiveTab(t.id)}
              className={`flex items-center gap-3 px-4 py-2.5 rounded-xl text-sm font-semibold
                          transition-colors w-full text-left
                ${activeTab === t.id
                  ? "bg-purple-500/10 text-purple-300 border border-purple-500/20"
                  : "text-sub hover:bg-white/5 hover:text-ink"}`}>
              <Ic n={t.icon} s={16} />{t.label}
            </button>
          ))}
        </div>

        <div className="flex-1 c-card p-6 sm:p-8 animate-fade-in">

          {/* ════════ PROFILE ════════ */}
          {activeTab === "profile" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Profile Information"
                sub="Changes are saved to the database via PUT /api/admin/me" />

              <div className="flex items-center gap-5">
                <div className="w-20 h-20 rounded-2xl flex items-center justify-center
                                text-3xl font-extrabold text-white shadow-purple-sm"
                     style={{ backgroundColor: avatarColor }}>
                  {avatarLetter}
                </div>
                <div>
                  <div className="text-sm font-bold text-ink mb-1">Avatar auto-assigned by username</div>
                  <div className="flex flex-wrap gap-1.5">
                    {Object.entries(AVATAR_COLORS).slice(0, 13).map(([l, c]) => (
                      <div key={l} title={l}
                        className="w-5 h-5 rounded-md text-[9px] font-extrabold text-white flex items-center justify-center"
                        style={{ backgroundColor: c }}>{l}
                      </div>
                    ))}
                    <span className="text-[10px] text-muted self-center">+13</span>
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <Label>Username (display name)</Label>
                  <input className="inp inp-md" value={username}
                    onChange={e => setUsername(e.target.value)}
                    placeholder="How your name appears in the dashboard" />
                  <p className="text-[11px] text-muted mt-1">
                    Stored as <span className="mono text-purple-300">display_name</span> in admin_users
                  </p>
                </div>
                <div>
                  <Label>Role</Label>
                  <input className="inp inp-md text-purple-300"
                    value={user?.role || "ADMIN"} disabled />
                </div>
                <div className="md:col-span-2">
                  <Label>Email Address</Label>
                  <input className="inp inp-md" type="email" value={email}
                    onChange={e => setEmail(e.target.value)} />
                  <p className="text-[11px] text-muted mt-1">
                    Used for password reset emails
                  </p>
                </div>
              </div>
              <div className="p-4 bg-elevated border border-border rounded-xl">
                <div className="text-xs font-bold text-sub uppercase tracking-wide mb-2">
                  Login Identity (read-only)
                </div>
                <div className="flex items-center gap-3">
                  <div className="flex-1">
                    <div className="text-xs text-muted">Login username</div>
                    <div className="mono text-sm font-bold text-ink">{user?.displayName || user?.username}</div>
                  </div>
                  <div className="flex-1">
                    <div className="text-xs text-muted">Role</div>
                    <div className="mono text-sm font-bold text-purple-300">{user?.role || "—"}</div>
                  </div>
                </div>
                <div className="text-[10px] text-muted mt-2">
                  Username and role can only be changed by a SUPER_ADMIN via the Admin Users tab.
                </div>
              </div>
              <SaveRow saving={saving} onSave={saveProfile} label="Save Profile" />
            </div>
          )}

          {/* ════════ SECURITY ════════ */}
          {activeTab === "security" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Change Password"
                sub="Calls PUT /api/auth/change-password — you will be signed out immediately after." />

              {pwCountdown !== null ? (
                <div className="flex flex-col items-center justify-center py-10 gap-4 text-center">
                  <div className="w-14 h-14 rounded-full bg-green-500/15 border border-green-500/25
                                  flex items-center justify-center">
                    <Ic n="check" s={24} c="#34D399" />
                  </div>
                  <div className="text-base font-bold text-ink">Password changed successfully</div>
                  <div className="text-sm text-sub">
                    Signing out in <span className="text-purple-300 font-bold">{pwCountdown}s</span>…
                  </div>
                  <div className="w-48 h-1.5 bg-elevated rounded-full overflow-hidden">
                    <div className="h-full bg-purple-500 rounded-full transition-all duration-1000"
                         style={{ width: `${(pwCountdown / 5) * 100}%` }} />
                  </div>
                </div>
              ) : (
                <div className="space-y-4 max-w-md">
                  <div>
                    <Label>Current Password</Label>
                    <input className="inp inp-md" type="password" placeholder="Enter current password"
                      value={curPw} onChange={e => setCurPw(e.target.value)}
                      autoComplete="current-password" />
                  </div>
                  <div>
                    <Label>New Password</Label>
                    <input className="inp inp-md" type="password" placeholder="Min. 8 characters"
                      value={newPw} onChange={e => setNewPw(e.target.value)}
                      autoComplete="new-password" />
                    {newPw && (
                      <div className="mt-2 flex items-center gap-3">
                        <div className="flex gap-1 flex-1">
                          {[1,2,3,4].map(i => (
                            <div key={i} className="flex-1 h-1.5 rounded-full transition-colors duration-300"
                                 style={{ backgroundColor: i <= strength ? strengthColor : "var(--elevated)" }} />
                          ))}
                        </div>
                        <span className="text-[11px] font-bold" style={{ color: strengthColor }}>
                          {strengthLabel}
                        </span>
                      </div>
                    )}
                  </div>
                  <div>
                    <Label>Confirm New Password</Label>
                    <input className="inp inp-md" type="password" placeholder="Repeat new password"
                      value={confPw} onChange={e => setConfPw(e.target.value)}
                      autoComplete="new-password" />
                    {confPw && newPw !== confPw && (
                      <div className="mt-1 text-[11px] text-danger flex items-center gap-1">
                        <Ic n="warning" s={11} c="#F87171" /> Passwords do not match
                      </div>
                    )}
                  </div>
                  <div className="pt-2">
                    <button type="button" onClick={savePassword}
                      disabled={saving || !curPw || !newPw || !confPw || newPw !== confPw}
                      className="btn btn-primary btn-md min-w-[180px] justify-center">
                      {saving ? <Spinner s={16} /> : "Change Password →"}
                    </button>
                  </div>
                </div>
              )}

              {/* ── Cryptographic Signing Key ── */}
              <div className="mt-6 pt-6 border-t border-border">
                <div className="flex items-start justify-between gap-4 mb-4 flex-wrap">
                  <div>
                    <div className="text-sm font-bold text-ink mb-1">Cryptographic Signing Key</div>
                    <div className="text-xs text-sub max-w-md leading-relaxed">
                      Required to approve sensitive actions (activate elections, deactivate admins, etc.)
                      via multi-signature. Your private key is generated in this browser and never
                      sent to the server.
                    </div>
                  </div>
                  {/* Status badge */}
                  <div className={`flex items-center gap-2 px-3 py-1.5 rounded-xl border text-xs font-semibold flex-shrink-0
                    ${hasLocalKey && hasServerKey
                      ? "bg-green-500/10 border-green-500/20 text-green-400"
                      : needsSetup
                      ? "bg-orange-500/10 border-orange-500/20 text-orange-300"
                      : "bg-elevated border-border text-muted"}`}>
                    <div className={`w-2 h-2 rounded-full ${hasLocalKey && hasServerKey ? "bg-green-400" : "bg-orange-400"}`} />
                    {hasLocalKey && hasServerKey
                      ? "Key registered"
                      : hasLocalKey && !hasServerKey
                      ? "Local only — re-register"
                      : "Not set up"}
                  </div>
                </div>

                {keyGenStep === "done" || (hasLocalKey && hasServerKey) ? (
                  <div className="flex flex-col gap-3 max-w-md">
                    <div className="bg-green-500/8 border border-green-500/20 rounded-xl p-4
                                    flex items-start gap-3">
                      <Ic n="check" s={16} c="#34D399" />
                      <div>
                        <div className="text-sm font-semibold text-green-300">Key active</div>
                        <div className="text-xs text-muted mt-0.5">
                          Your ECDSA P-256 private key is stored in this browser.
                          You can sign and approve pending actions in the Approvals tab.
                        </div>
                      </div>
                    </div>
                    {/* Key rotation */}
                    <button
                      onClick={() => setKeyGenStep("idle")}
                      className="btn btn-surface btn-sm gap-2 self-start">
                      <Ic n="refresh" s={13} /> Rotate Key
                    </button>
                  </div>
                ) : keyGenStep === "generating" ? (
                  <div className="flex items-center gap-3 py-4">
                    <Spinner s={20} />
                    <div className="text-sm text-sub">Generating ECDSA P-256 keypair in browser…</div>
                  </div>
                ) : keyGenStep === "error" ? (
                  <div className="flex flex-col gap-3 max-w-md">
                    <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-start gap-3">
                      <Ic n="warning" s={15} c="#F87171" />
                      <div className="text-sm text-danger">{keyGenError}</div>
                    </div>
                    <button onClick={handleGenerateKey} className="btn btn-primary btn-sm gap-2 self-start">
                      <Ic n="refresh" s={13} c="#fff" /> Try Again
                    </button>
                  </div>
                ) : (
                  <div className="flex flex-col gap-4 max-w-md">
                    <div className="bg-orange-500/8 border border-orange-500/20 rounded-xl p-4
                                    text-xs text-orange-200 leading-relaxed">
                      <strong>Important:</strong> Your private key will be stored only in this browser.
                      If you clear browser data or switch browsers you will need to generate a new key.
                      The server stores only your public key for signature verification.
                    </div>
                    <button
                      onClick={handleGenerateKey}
                      disabled={generating}
                      className="btn btn-primary btn-md gap-2 self-start">
                      <Ic n="shield" s={15} c="#fff" />
                      Generate Signing Key
                    </button>
                  </div>
                )}
              </div>

              {/* ── Two-Factor Authentication ── */}
              <div className="mt-6 pt-6 border-t border-border">
                <div className="text-sm font-bold text-ink mb-1">Two-Factor Authentication</div>
                <div className="text-xs text-sub mb-4">TOTP (Google Authenticator / Authy)</div>
                <button className="btn btn-surface btn-sm opacity-50 cursor-not-allowed" disabled>
                  <Ic n="lock" s={14} /> Enable 2FA — coming soon
                </button>
              </div>
            </div>
          )}

          {/* ════════ NOTIFICATIONS ════════ */}
          {activeTab === "notifications" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Notification Preferences"
                sub="Saved to admin_preferences table via PUT /api/admin/settings/notifications" />
              <div>
                <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-3">Email</div>
                <div className="c-card p-4">
                  <Toggle checked={notif.emailVoteCast}   onChange={v=>setNotif(p=>({...p,emailVoteCast:v}))}
                    label="Vote Cast Events"      sub="Email on successful ballot submission" />
                  <Toggle checked={notif.emailEnrollment} onChange={v=>setNotif(p=>({...p,emailEnrollment:v}))}
                    label="Enrollment Completions" sub="Email when card personalisation succeeds" />
                  <Toggle checked={notif.emailTamper}     onChange={v=>setNotif(p=>({...p,emailTamper:v}))}
                    label="Tamper Alerts"          sub="Immediate alert on terminal casing breach" />
                  <Toggle checked={notif.emailAuth}       onChange={v=>setNotif(p=>({...p,emailAuth:v}))}
                    label="Auth Failures"          sub="Email when liveness or PIN check fails" />
                </div>
              </div>
              <div>
                <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-3">In-App</div>
                <div className="c-card p-4">
                  <Toggle checked={notif.inAppAll} onChange={v=>setNotif(p=>({...p,inAppAll:v}))}
                    label="All In-App Notifications" sub="Show notification bell in the top bar" />
                </div>
              </div>
              <div>
                <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-3">SMS</div>
                <div className="c-card p-4">
                  <Toggle checked={notif.smsAlerts} onChange={v=>setNotif(p=>({...p,smsAlerts:v}))}
                    label="SMS Critical Alerts" sub="Tamper and offline terminal alerts (requires NCC integration)" />
                </div>
              </div>
              <SaveRow saving={saving}
                onSave={() => savePrefs("notifications", notif, "Notification preferences")} />
            </div>
          )}

          {/* ════════ DISPLAY ════════ */}
          {activeTab === "display" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Display Preferences"
                sub="Theme applies immediately. Other settings saved to DB." />
              <div>
                <Label>Theme</Label>
                <div className="flex gap-3">
                  {[["dark","Dark","moon"],["light","Light","sun"]].map(([val,lbl,icon]) => (
                    <button key={val} type="button" onClick={() => setTheme(val)}
                      className={`flex-1 flex items-center justify-center gap-2 py-3 rounded-xl
                                  border text-sm font-semibold transition-colors
                        ${theme === val
                          ? "border-purple-500/50 bg-purple-500/10 text-purple-300"
                          : "border-border bg-elevated text-sub hover:border-purple-500/30"}`}>
                      <Ic n={icon} s={16} />{lbl}
                      {theme === val && <Ic n="check" s={13} c="#A78BFA" />}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <Label>UI Density</Label>
                  <select className="inp inp-md" value={density} onChange={e => setDensity(e.target.value)}>
                    <option value="compact">Compact</option>
                    <option value="comfortable">Comfortable</option>
                    <option value="spacious">Spacious</option>
                  </select>
                </div>
                <div>
                  <Label>Font Size</Label>
                  <select className="inp inp-md" value={fontSize} onChange={e => setFontSize(e.target.value)}>
                    <option value="sm">Small (13px)</option>
                    <option value="base">Default (14px)</option>
                    <option value="lg">Large (16px)</option>
                  </select>
                </div>
                <div>
                  <Label>Date Format</Label>
                  <select className="inp inp-md" value={dateFormat} onChange={e => setDateFormat(e.target.value)}>
                    <option value="dd/MM/yyyy">DD/MM/YYYY — Nigerian standard</option>
                    <option value="MM/dd/yyyy">MM/DD/YYYY</option>
                    <option value="yyyy-MM-dd">YYYY-MM-DD — ISO 8601</option>
                  </select>
                </div>
                <div>
                  <Label>Language</Label>
                  <select className="inp inp-md" value={language} onChange={e => setLanguage(e.target.value)}>
                    <option value="en-NG">English (Nigeria)</option>
                    <option value="en-GB">English (UK)</option>
                    <option value="ha">Hausa</option>
                    <option value="yo">Yorùbá</option>
                    <option value="ig">Igbo</option>
                  </select>
                </div>
              </div>
              <SaveRow saving={saving}
                onSave={() => savePrefs("display", { density, fontSize, dateFormat, language }, "Display preferences")} />
            </div>
          )}

          {/* ════════ SESSION ════════ */}
          {activeTab === "session" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Session & Access Control"
                sub="Configure timeouts and dual-admin approval policies." />
              <div>
                <Label>Auto-logout After Inactivity</Label>
                <select className="inp inp-md max-w-xs" value={autoLogout}
                  onChange={e => setAutoLogout(e.target.value)}>
                  <option value="15">15 minutes</option>
                  <option value="30">30 minutes</option>
                  <option value="60">1 hour</option>
                  <option value="120">2 hours</option>
                  <option value="0">Never (not recommended)</option>
                </select>
              </div>
              <div className="c-card p-4">
                <Toggle checked={dualApproval} onChange={setDualApproval}
                  label="Dual-Admin Approval"
                  sub="Critical actions (election activate/close) require a second SUPER_ADMIN sign-off" />
                <Toggle checked={sessionLogs} onChange={setSessionLogs}
                  label="Log All Sessions"
                  sub="Write ADMIN_LOGIN and ADMIN_LOGOUT to the audit trail" />
              </div>
              <SaveRow saving={saving}
                onSave={() => savePrefs("session", { autoLogout, dualApproval, sessionLogs }, "Session settings")} />
            </div>
          )}

          {/* ════════ TERMINALS ════════ */}
          {activeTab === "terminals" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="Terminal Fleet Configuration"
                sub="Global defaults applied to all ESP32-S3 hardware terminals." />
              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <Label>Heartbeat Interval (seconds)</Label>
                  <input className="inp inp-md" type="number" min="10" max="300"
                    value={heartbeat} onChange={e => setHeartbeat(e.target.value)} />
                  <p className="text-[11px] text-muted mt-1">
                    How often terminals call <span className="mono text-purple-300">POST /api/terminal/heartbeat</span>
                  </p>
                </div>
                <div>
                  <Label>Offline Threshold (seconds)</Label>
                  <input className="inp inp-md" type="number" min="60" max="3600"
                    value={offlineThresh} onChange={e => setOfflineThresh(e.target.value)} />
                  <p className="text-[11px] text-muted mt-1">
                    Terminal marked OFFLINE after this many seconds without heartbeat
                  </p>
                </div>
              </div>
              <div className="c-card p-4">
                <Toggle checked={autoLock} onChange={setAutoLock}
                  label="Auto-lock on Tamper Detection"
                  sub="Immediately lock card reader when anti-tamper switch triggers" />
              </div>
              <SaveRow saving={saving}
                onSave={() => savePrefs("terminals", { heartbeat, offlineThresh, autoLock }, "Terminal config")} />
            </div>
          )}

        {/* ════════ SYSTEM ════════ */}
                  {activeTab === "system" && (
                    <div className="space-y-6 animate-fade-up">
                      <SectionHeader title="System Configuration"
                        sub="Audit retention, backups, and Merkle root publishing." />
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                        <div>
                          <Label>Audit Log Retention (days)</Label>
                          <select className="inp inp-md" value={auditRetention}
                            onChange={e => setAuditRetention(e.target.value)}>
                            <option value="90">90 days</option>
                            <option value="180">180 days</option>
                            <option value="365">1 year</option>
                            <option value="1825">5 years (INEC standard)</option>
                            <option value="0">Keep forever</option>
                          </select>
                        </div>
                        <div>
                          <Label>Backup Schedule</Label>
                          <select className="inp inp-md" value={backupSchedule}
                            onChange={e => setBackupSchedule(e.target.value)}>
                            <option value="hourly">Hourly</option>
                            <option value="daily">Daily (midnight)</option>
                            <option value="weekly">Weekly (Sunday)</option>
                          </select>
                        </div>
                      </div>

                      {/* Toggles */}
                      <div className="c-card p-4">
                        <Toggle checked={merklePublish} onChange={setMerklePublish}
                          label="Auto-publish Merkle Root"
                          sub="Commit ballot Merkle root to public ledger after each vote batch" />
                        <Toggle
                          checked={livenessFailOpen}
                          onChange={setLivenessFailOpen}
                          label="Liveness Fail-Open Mode"
                          sub={livenessFailOpen
                            ? "⚠ Weak — if the AI liveness service is unreachable, basic JPEG validation is used instead. Votes may pass without real AI evaluation."
                            : "✓ Strict — if the AI liveness service is unreachable, the vote is blocked. Requires MiniFASNet service to be running."}
                        />
                      </div>

                      {livenessFailOpen && (
                        <div className="flex items-start gap-3 px-4 py-3 rounded-xl
                                       bg-amber-500/8 border border-amber-500/20">
                          <Ic n="warning" s={14} c="#FCD34D" />
                          <div className="text-xs text-amber-300 leading-relaxed">
                            <span className="font-bold">Security Warning:</span> Fail-open mode reduces
                            liveness security. Only enable this during controlled lab testing when the
                            Python MiniFASNet service cannot be run. Disable before any real election.
                          </div>
                        </div>
                      )}

                      {/* ── THE MISSING MANUAL ACTIONS BLOCK ── */}
                      <div>
                        <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-3">
                          Manual Actions
                        </div>

                        {confirmPublish ? (
                          <div className="flex flex-col gap-3 p-4 bg-red-500/10 border border-red-500/20 rounded-xl max-w-md animate-fade-in">
                            <div className="flex items-start gap-3">
                              <div className="mt-0.5"><Ic n="warning" s={16} c="#F87171" /></div>
                              <div>
                                <div className="text-sm font-bold text-danger">Are you absolutely sure?</div>
                                <div className="text-xs text-red-200 mt-1 leading-relaxed">
                                  Publishing the Merkle root will anchor the current election tally to the public ledger. This action is immutable and will initiate a Multi-Sig request.
                                </div>
                              </div>
                            </div>
                            <div className="flex items-center gap-3 mt-2 pl-7">
                              <button
                                className="btn btn-sm bg-red-500 text-white hover:bg-red-600 border-none gap-2 font-bold"
                                onClick={() => {
                                  setConfirmPublish(false);
                                  publishMerkle();
                                }}>
                                Yes, Publish Root
                              </button>
                              <button
                                className="btn btn-surface btn-sm hover:text-white"
                                onClick={() => setConfirmPublish(false)}>
                                Cancel
                              </button>
                            </div>
                          </div>
                        ) : (
                          <div className="flex flex-wrap gap-3">
                            <button className="btn btn-surface btn-sm" onClick={exportAuditLog}>
                              <Ic n="database" s={13} /> Export Audit Log (JSON)
                            </button>
                            <button
                              className="btn btn-surface btn-sm border-orange-500/30 text-orange-300 hover:bg-orange-500/10"
                              onClick={() => setConfirmPublish(true)}>
                              <Ic n="shield" s={13} c="#FDBA74" /> Publish Merkle Root
                            </button>
                          </div>
                        )}
                      </div>

                      <SaveRow saving={saving}
                        onSave={async () => {
                          await savePrefs("system",
                            { auditRetention, backupSchedule, merklePublish, livenessFailOpen },
                            "System config");
                          try {
                            await client.put("/admin/system/liveness-config", { failOpen: livenessFailOpen });
                          } catch (e) {
                            console.warn("Could not update liveness config at runtime:", e.message);
                          }
                        }} />
                    </div>
                  )}
          {/* ════════ ABOUT ════════ */}
          {activeTab === "about" && (
            <div className="space-y-6 animate-fade-up">
              <SectionHeader title="About This System" sub="Version information and live health status." />
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {[
                  { label: "System",      value: "MFA E-Voting Portal" },
                  { label: "Frontend",    value: "v5.0 — React 18 + Vite" },
                  { label: "Backend",     value: "Spring Boot v4.0" },
                  { label: "Smart Card",  value: "JCOP4 Applet v2.0" },
                  { label: "Firmware",    value: "ESP32-S3/CAM v4.0" },
                  { label: "Compliance",  value: "INEC 2027 Framework" },
                ].map(r => (
                  <div key={r.label} className="c-card p-4 flex items-start justify-between gap-2">
                    <span className="text-xs font-bold text-sub">{r.label}</span>
                    <span className="text-xs text-ink mono text-right">{r.value}</span>
                  </div>
                ))}
              </div>

              <div>
                <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-3">
                  Live System Health
                </div>
                {healthLoading ? (
                  <div className="flex justify-center py-6"><Spinner s={24} /></div>
                ) : (
                  <div className="c-card divide-y divide-border/40">
                    {[
                      { label: "Spring Boot API",   ok: !!health,                  detail: health ? "Responding" : "Unreachable" },
                      { label: "Registered Voters", ok: true,                      detail: health ? health.registeredVoters?.toLocaleString() : "—" },
                      { label: "Votes Cast",        ok: true,                      detail: health ? health.votesCast?.toLocaleString() : "—" },
                      { label: "Online Terminals",  ok: (health?.onlineTerminals || 0) > 0, detail: health ? `${health.onlineTerminals} / ${health.totalTerminals}` : "—" },
                      { label: "Active Elections",  ok: (health?.activeElections || 0) > 0, detail: health ? `${health.activeElections} active` : "—" },
                    ].map(s => (
                      <div key={s.label} className="flex items-center justify-between px-4 py-3">
                        <span className="text-xs font-semibold text-sub">{s.label}</span>
                        <div className="flex items-center gap-3">
                          <span className="mono text-[10px] text-muted">{s.detail}</span>
                          <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border
                            ${s.ok
                              ? "text-success border-green-500/30 bg-green-500/10"
                              : "text-warning border-yellow-500/30 bg-yellow-500/10"}`}>
                            {s.ok ? "OK" : "—"}
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
