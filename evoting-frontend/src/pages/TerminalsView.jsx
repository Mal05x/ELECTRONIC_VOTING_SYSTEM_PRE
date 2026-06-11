import { useState, useEffect, useRef } from "react";
import { useAuth } from "../context/AuthContext.jsx";
import {
  getTerminals, resolveTamperAlert,
  getTerminalRegistry, provisionTerminal, setOfficerPin, deactivateTerminal,
} from "../api/terminals.js";
import {
  StatCard, StatusBadge, SectionHeader, EmptyState, Spinner, Label,
} from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

// ── Toast ─────────────────────────────────────────────────────────────────────

function Toast({ msg, type, onClose }) {
  if (!msg) return null;
  return (
    <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                     px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                     ${type === "error"   ? "text-danger"      :
                       type === "warning" ? "text-yellow-300"  : "text-purple-300"}`}>
      <Ic n={type === "error" ? "warning" : type === "warning" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="text-muted hover:text-sub ml-1">
        <Ic n="close" s={12} />
      </button>
    </div>
  );
}

// ── Tab button ────────────────────────────────────────────────────────────────

function Tab({ id, active, onClick, children }) {
  return (
    <button
      onClick={() => onClick(id)}
      className={`px-5 py-2.5 text-sm font-semibold rounded-xl transition-all
        ${active
          ? "bg-purple-500/15 text-purple-300 border border-purple-500/30"
          : "text-sub hover:text-ink hover:bg-elevated"}`}>
      {children}
    </button>
  );
}

// ── Officer PIN Modal ─────────────────────────────────────────────────────────
//
// Shown when a SUPER_ADMIN clicks "Set PIN" or "Rotate PIN" on a terminal row.
//
// Flow:
//   1. Admin enters a 6-digit PIN and a confirmation of the same PIN.
//   2. Client validates: exactly 6 digits, both fields match.
//   3. On submit → PUT /api/admin/terminals/{id}/officer-pin
//   4. On success → show "Record this PIN" screen with the plain PIN visible.
//      The admin must explicitly confirm they have recorded it before closing.
//
// Security note:
//   The plain PIN is visible in this modal only. After the admin closes the
//   "Record" screen, the PIN cannot be retrieved — only reset. The backend
//   hashes and discards the plain value immediately on receipt.

function SetOfficerPinModal({ terminal, onClose, onSuccess }) {
  const [pin,         setPin]         = useState("");
  const [confirm,     setConfirm]     = useState("");
  const [showPin,     setShowPin]     = useState(false);
  const [saving,      setSaving]      = useState(false);
  const [error,       setError]       = useState("");
  const [recorded,    setRecorded]    = useState(false); // "record" confirmation screen
  const [savedPin,    setSavedPin]    = useState("");    // plain PIN shown after success
  const pinRef = useRef(null);

  useEffect(() => {
    setTimeout(() => pinRef.current?.focus(), 80);
  }, []);

  const isRotate = terminal.pinProvisioned;

  // Validate inline
  const pinValid     = /^\d{6}$/.test(pin);
  const confirmMatch = pin === confirm;
  const canSubmit    = pinValid && confirmMatch && pin.length === 6;

  const handleSubmit = async () => {
    setError("");
    if (!pinValid)     { setError("PIN must be exactly 6 digits (0–9 only)"); return; }
    if (!confirmMatch) { setError("The two PIN entries do not match"); return; }

    setSaving(true);
    try {
      await setOfficerPin(terminal.terminalId, pin);
      setSavedPin(pin);
      setRecorded(false); // show "record" screen
    } catch (e) {
      setError(e.response?.data?.error || e.message || "Failed to set officer PIN");
    } finally {
      setSaving(false);
    }
  };

  // ── Success / Record screen ─────────────────────────────────────────────────
  if (savedPin) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
        <div className="bg-card border border-border-hi rounded-2xl shadow-card w-full max-w-md animate-fade-up">

          {/* Header */}
          <div className="flex items-center justify-between px-6 pt-5 pb-4 border-b border-border">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-full bg-green-500/15 flex items-center justify-center">
                <Ic n="check" s={16} c="#4ADE80" />
              </div>
              <h2 className="text-sm font-bold text-ink">
                PIN {isRotate ? "Rotated" : "Set"} — Record It Now
              </h2>
            </div>
          </div>

          <div className="px-6 py-5 space-y-4">

            {/* Security warning */}
            <div className="flex items-start gap-3 bg-amber-500/10 border border-amber-500/25
                            rounded-xl px-4 py-3">
              <Ic n="warning" s={15} c="#FCD34D" className="mt-0.5 flex-shrink-0" />
              <p className="text-xs text-amber-200 leading-relaxed">
                <span className="font-bold">This is the only time the plain PIN is visible.</span>
                {" "}The backend hashed and discarded it. If you close this dialog without recording
                the PIN, it cannot be recovered — only reset.
              </p>
            </div>

            {/* Plain PIN display */}
            <div className="bg-elevated border border-border-hi rounded-xl p-5 text-center space-y-2">
              <p className="text-xs text-muted font-semibold uppercase tracking-widest">
                Officer PIN for {terminal.terminalId}
              </p>
              <div className="flex items-center justify-center gap-3">
                <span className="mono text-4xl font-black text-purple-300 tracking-[0.35em]">
                  {savedPin}
                </span>
                <button
                  onClick={() => navigator.clipboard.writeText(savedPin)}
                  className="text-muted hover:text-sub transition-colors"
                  title="Copy PIN">
                  <Ic n="database" s={16} />
                </button>
              </div>
              <p className="text-xs text-muted">
                Communicate this PIN to the Returning Officer via{" "}
                <span className="text-sub font-semibold">
                  a sealed envelope or secure out-of-band channel.
                </span>
              </p>
            </div>

            {/* Record confirmation */}
            <label className="flex items-start gap-3 cursor-pointer group">
              <input
                type="checkbox"
                checked={recorded}
                onChange={e => setRecorded(e.target.checked)}
                className="mt-0.5 accent-purple-500 w-4 h-4 flex-shrink-0"
              />
              <span className="text-xs text-sub group-hover:text-ink transition-colors leading-relaxed">
                I have securely recorded this PIN and will communicate it to the
                Returning Officer via a tamper-evident physical channel before election day.
              </span>
            </label>

          </div>

          <div className="px-6 pb-5 flex gap-3">
            <button
              className="btn btn-purple btn-sm flex-1"
              disabled={!recorded}
              onClick={() => { onSuccess(); onClose(); }}>
              <Ic n="check" s={13} /> Done — Close
            </button>
          </div>
        </div>
      </div>
    );
  }

  // ── PIN entry form ──────────────────────────────────────────────────────────
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <div className="bg-card border border-border-hi rounded-2xl shadow-card w-full max-w-md animate-fade-up">

        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-5 pb-4 border-b border-border">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-full bg-amber-500/15 flex items-center justify-center">
              <Ic n="shield" s={16} c="#FCD34D" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-ink">
                {isRotate ? "Rotate Officer PIN" : "Set Officer PIN"}
              </h2>
              <p className="text-xs text-muted mono">{terminal.terminalId}</p>
            </div>
          </div>
          <button onClick={onClose} className="text-muted hover:text-sub">
            <Ic n="close" s={16} />
          </button>
        </div>

        <div className="px-6 py-5 space-y-5">

          {/* How it works */}
          <div className="flex items-start gap-3 bg-purple-500/8 border border-purple-500/20
                          rounded-xl px-4 py-3">
            <Ic n="shield" s={14} c="#A78BFA" className="mt-0.5 flex-shrink-0" />
            <p className="text-xs text-sub leading-relaxed">
              The PIN you enter will be <span className="text-purple-300 font-semibold">
              hashed with SHA-256 immediately</span> and the plain value discarded.
              The terminal fetches the hash on its next boot via its ECDSA-authenticated
              channel — it never sees the plain PIN. Communicate the plain PIN to the
              Returning Officer <span className="font-semibold text-sub">out-of-band</span> after setting it.
            </p>
          </div>

          {/* Error */}
          {error && (
            <div className="flex items-start gap-2 bg-red-500/10 border border-red-500/25 rounded-xl px-4 py-3">
              <Ic n="warning" s={14} c="#F87171" className="mt-0.5 flex-shrink-0" />
              <span className="text-sm text-red-300">{error}</span>
            </div>
          )}

          {/* Rotate warning */}
          {isRotate && (
            <div className="flex items-start gap-2 bg-amber-500/10 border border-amber-500/25 rounded-xl px-4 py-3">
              <Ic n="warning" s={14} c="#FCD34D" className="mt-0.5 flex-shrink-0" />
              <p className="text-xs text-amber-200 leading-relaxed">
                Rotating the PIN immediately invalidates the old one. The terminal picks up the
                new hash on its next boot. Notify the Returning Officer of the change before
                election day.
              </p>
            </div>
          )}

          {/* PIN input */}
          <div className="space-y-3">
            <div>
              <Label>Officer PIN <span className="text-danger">*</span></Label>
              <div className="relative">
                <input
                  ref={pinRef}
                  type={showPin ? "text" : "password"}
                  inputMode="numeric"
                  maxLength={6}
                  className={`inp inp-md mono w-full text-center text-xl tracking-[0.5em] pr-10
                    ${pin.length > 0 && !pinValid ? "border-danger/60" : ""}`}
                  placeholder="••••••"
                  value={pin}
                  onChange={e => {
                    const v = e.target.value.replace(/\D/g, "").slice(0, 6);
                    setPin(v);
                    setError("");
                  }}
                  onKeyDown={e => e.key === "Enter" && confirm.length === 6 && handleSubmit()}
                />
                <button
                  type="button"
                  onClick={() => setShowPin(p => !p)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted hover:text-sub">
                  <Ic n={showPin ? "eye-off" : "eye"} s={15} />
                </button>
              </div>
              {pin.length > 0 && pin.length < 6 && (
                <p className="text-[11px] text-warning mt-1">
                  {6 - pin.length} more digit{6 - pin.length !== 1 ? "s" : ""} needed
                </p>
              )}
            </div>

            <div>
              <Label>Confirm PIN <span className="text-danger">*</span></Label>
              <input
                type={showPin ? "text" : "password"}
                inputMode="numeric"
                maxLength={6}
                className={`inp inp-md mono w-full text-center text-xl tracking-[0.5em]
                  ${confirm.length === 6 && !confirmMatch ? "border-danger/60" : ""}
                  ${confirm.length === 6 &&  confirmMatch ? "border-success/60" : ""}`}
                placeholder="••••••"
                value={confirm}
                onChange={e => {
                  const v = e.target.value.replace(/\D/g, "").slice(0, 6);
                  setConfirm(v);
                  setError("");
                }}
                onKeyDown={e => e.key === "Enter" && canSubmit && handleSubmit()}
              />
              {confirm.length === 6 && !confirmMatch && (
                <p className="text-[11px] text-danger mt-1">PINs do not match</p>
              )}
              {confirm.length === 6 && confirmMatch && (
                <p className="text-[11px] text-success mt-1">✓ PINs match</p>
              )}
            </div>
          </div>

          {/* 6-dot visualiser */}
          <div className="flex items-center justify-center gap-2.5 py-1">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i}
                className={`w-3 h-3 rounded-full transition-all duration-150
                  ${i < pin.length
                    ? confirmMatch && pin.length === 6
                      ? "bg-success scale-110"
                      : "bg-amber-400 scale-110"
                    : "bg-elevated border border-border-hi"}`}
              />
            ))}
          </div>

        </div>

        {/* Actions */}
        <div className="px-6 pb-5 flex gap-3 border-t border-border pt-4">
          <button
            className="btn btn-purple btn-sm flex-1"
            onClick={handleSubmit}
            disabled={!canSubmit || saving}>
            {saving
              ? <><Spinner s={13} /> Hashing & saving…</>
              : <><Ic n={isRotate ? "refresh" : "shield"} s={13} />
                  {isRotate ? "Rotate PIN" : "Set PIN"}</>}
          </button>
          <button className="btn btn-surface btn-sm" onClick={onClose} disabled={saving}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  TAB 1 — LIVE MONITORING
// ─────────────────────────────────────────────────────────────────────────────

function MonitoringTab() {
  const [terminals,   setTerminals]   = useState([]);
  const [loading,     setLoading]     = useState(true);
  const [resolving,   setResolving]   = useState(null);
  const [toast,       setToast]       = useState({ msg: "", type: "success" });
  // BUG-14 FIX: track last successful refresh so officers know if data is stale
  const [lastRefresh, setLastRefresh] = useState(null);
  const [refreshing,  setRefreshing]  = useState(false);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  const load = async (silent = false) => {
    if (!silent) setLoading(true);
    else setRefreshing(true);
    try {
      const data = await getTerminals();
      const now  = Date.now();
      const mapped = data.map(t => {
        const timestampStr = t.reportedAt || t.lastSeen || t.lastHeartbeat;
        const lastMs  = timestampStr ? new Date(timestampStr).getTime() : 0;
        const ageSecs = (now - lastMs) / 1000;
        let status = "OFFLINE";
        if (lastMs > 0) {
          if (Math.abs(ageSecs) < 300)  status = "ONLINE";
          else if (Math.abs(ageSecs) < 900) status = "WARNING";
        }
        if (t.tamperFlag) status = "ALERT";
        return {
          id:       t.terminalId,
          battery:  t.batteryLevel ?? 0,
          status,
          tamper:   t.tamperFlag || false,
          ip:       t.ipAddress || "—",
          lastSeen: lastMs > 0
            ? new Date(lastMs).toLocaleTimeString("en-NG", { hour:"2-digit", minute:"2-digit", second:"2-digit" })
            : "Never",
        };
      });
      setTerminals(mapped);
      setLastRefresh(new Date()); // BUG-14 FIX: record when data was last fetched
    } catch (e) {
      if (!silent) setTerminals([]);
    } finally {
      if (!silent) setLoading(false);
      else setRefreshing(false);
    }
  };

  useEffect(() => {
    load();
    const iv = setInterval(() => load(true), 10_000);
    return () => clearInterval(iv);
  }, []);

  const resolveTamper = async (id) => {
    setResolving(id);
    try {
      setTerminals(p => p.map(t => t.id === id ? { ...t, tamper: false, status: "ONLINE" } : t));
      await resolveTamperAlert(id);
      showToast(`Tamper alert cleared for ${id}`);
    } catch (e) {
      showToast("Failed to resolve alert: " + (e.response?.data?.error || e.message), "error");
      load(true);
    } finally { setResolving(null); }
  };

  const counts = {
    online:   terminals.filter(t => t.status === "ONLINE").length,
    offline:  terminals.filter(t => t.status === "OFFLINE").length,
    tampered: terminals.filter(t => t.tamper).length,
    lowBat:   terminals.filter(t => t.battery > 0 && t.battery <= 20).length,
  };

  const statusColor = s => ({
    ONLINE:"text-success", WARNING:"text-warning",
    ALERT:"text-danger",   OFFLINE:"text-muted"
  }[s] || "text-muted");

  return (
    <>
      <Toast msg={toast.msg} type={toast.type} onClose={() => setToast({ msg:"", type:"success" })} />

      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard label="Online"        value={counts.online}   icon="chip"    accent="green" delay={0} />
        <StatCard label="Offline"       value={counts.offline}  icon="warning" accent="amber" delay={50} />
        <StatCard label="Low Battery"   value={counts.lowBat}   icon="trend"   accent="blue"  delay={100} />
        <StatCard label="Tamper Alerts" value={counts.tampered} icon="shield"  accent="red"   delay={150} />
      </div>

      <div className="c-card p-6 animate-fade-up" style={{ animationDelay:"200ms" }}>
        <SectionHeader
          title="Hardware Fleet Monitoring"
          sub="ESP32-S3 terminal heartbeats and security status — updates every 10s"
          action={
            <div className="flex items-center gap-3">
              {/* BUG-14 FIX: last-refresh timestamp so officers know data freshness */}
              {lastRefresh && (
                <span className="text-[11px] text-muted font-mono hidden sm:block">
                  Updated {lastRefresh.toLocaleTimeString("en-NG", { hour:"2-digit", minute:"2-digit", second:"2-digit" })}
                </span>
              )}
              <div className={`flex items-center gap-2 text-xs font-bold px-3 py-1.5 rounded-lg border
                ${refreshing
                  ? "text-amber-300 bg-amber-500/10 border-amber-500/20"
                  : "text-success bg-green-500/10 border-green-500/20"}`}>
                <span className={refreshing ? "animate-spin inline-block" : "live-dot"}>
                  {refreshing ? "↻" : ""}
                </span>
                {refreshing ? "Refreshing…" : "Live"}
              </div>
              <button
                className="btn btn-surface btn-sm"
                aria-label="Refresh terminal status"
                onClick={() => load(false)}>
                <Ic n="refresh" s={13} />
              </button>
            </div>
          }
        />

        <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
          style={{ gridTemplateColumns:"120px 100px 120px 100px 140px 1fr" }}>
          {["Terminal ID","Battery","Status","Tamper","Last Heartbeat","Action"].map(h => (
            <span key={h} className="sect-lbl">{h}</span>
          ))}
        </div>
        <hr className="divider mb-1" />

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28} /></div>
        ) : terminals.length === 0 ? (
          <EmptyState
            icon="chip"
            title="No heartbeats received yet"
            sub="Terminals appear here once they start sending POST /api/terminal/heartbeat"
          />
        ) : (
          terminals.map((t, i) => (
            <div key={t.id}
              className={`trow animate-fade-up ${t.tamper ? "bg-red-500/5 border-l-2 border-l-danger" : ""}`}
              style={{ gridTemplateColumns:"120px 100px 120px 100px 140px 1fr", gap:"12px", animationDelay:`${i*20}ms` }}>

              <span className="mono text-[12px] font-bold text-purple-400">{t.id}</span>

              <div className="flex items-center gap-2">
                <div className="w-7 h-3.5 border border-sub/60 rounded-[3px] p-px relative bg-black/20 flex-shrink-0">
                  <div className={`h-full rounded-[2px] transition-all ${t.battery > 20 ? "bg-success" : "bg-danger"}`}
                    style={{ width:`${Math.max(t.battery, 0)}%` }} />
                </div>
                <span className="mono text-[11px] text-sub">{t.battery}%</span>
              </div>

              <span className={`text-[11px] font-bold ${statusColor(t.status)}`}>● {t.status}</span>

              <span>
                {t.tamper
                  ? <span className="badge badge-red text-[9px]">⚑ ALERT</span>
                  : <span className="badge badge-grey text-[9px]">✓ Clear</span>}
              </span>

              <span className="mono text-[11px] text-muted">{t.lastSeen}</span>

              <div className="flex gap-1.5">
                {t.tamper && (
                  <button className="btn btn-danger btn-sm !text-[11px]"
                    onClick={() => resolveTamper(t.id)} disabled={resolving === t.id}>
                    {resolving === t.id ? <Spinner s={11} /> : <Ic n="shield" s={11} />}
                    Clear Alert
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  TAB 2 — TERMINAL REGISTRY
// ─────────────────────────────────────────────────────────────────────────────

const EMPTY_FORM = { terminalId:"", publicKey:"", label:"", pollingUnitId:"" };

function RegistryTab() {
  const { user } = useAuth();
  const isSuperAdmin = user?.role === "SUPER_ADMIN";

  const [registry,    setRegistry]    = useState([]);
  const [loading,     setLoading]     = useState(true);
  const [showForm,    setShowForm]    = useState(false);
  const [form,        setForm]        = useState(EMPTY_FORM);
  const [saving,      setSaving]      = useState(false);
  const [formError,   setFormError]   = useState("");
  const [toast,       setToast]       = useState({ msg:"", type:"success" });
  const [rotateId,    setRotateId]    = useState(null);
  const [pinModal,    setPinModal]    = useState(null); // terminal object for PIN modal
  // BUG-11 FIX: track which terminal is being deactivated
  const [deactivating, setDeactivating] = useState(null);
  const pubKeyRef                     = useRef(null);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg:"", type:"success" }), 4000);
  };

  const load = async () => {
    setLoading(true);
    try {
      const data = await getTerminalRegistry();
      setRegistry(data);
    } catch (e) {
      setRegistry([]);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openProvision = (existing = null) => {
    if (existing) {
      setForm({ terminalId: existing.terminalId, publicKey:"", label: existing.label || "", pollingUnitId: existing.pollingUnitId || "" });
      setRotateId(existing.terminalId);
    } else {
      setForm(EMPTY_FORM);
      setRotateId(null);
    }
    setFormError("");
    setShowForm(true);
    setTimeout(() => pubKeyRef.current?.focus(), 100);
  };

  const handleSubmit = async () => {
    setFormError("");
    if (!form.terminalId.trim()) { setFormError("Terminal ID is required"); return; }
    if (!form.publicKey.trim())  { setFormError("Public key is required"); return; }
    if (!/^[A-Za-z0-9+/]+=*$/.test(form.publicKey.trim())) {
      setFormError("Public key must be a valid Base64 string");
      return;
    }
    if (form.publicKey.trim().length < 80) {
      setFormError("Public key appears too short. Make sure you copied the full Base64 string.");
      return;
    }

    setSaving(true);
    try {
      const payload = {
        terminalId:    form.terminalId.trim(),
        publicKey:     form.publicKey.trim(),
        label:         form.label.trim() || form.terminalId.trim(),
        pollingUnitId: form.pollingUnitId ? parseInt(form.pollingUnitId) : undefined,
      };
      await provisionTerminal(payload);
      showToast(rotateId
        ? `Key rotated for ${payload.terminalId}`
        : `Terminal ${payload.terminalId} provisioned — set its Officer PIN next`);
      setShowForm(false);
      setForm(EMPTY_FORM);
      setRotateId(null);
      load();
    } catch (e) {
      setFormError(e.response?.data?.error || e.message || "Provisioning failed");
    } finally { setSaving(false); }
  };

  const pasteFromClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      setForm(p => ({ ...p, publicKey: text.trim() }));
      setFormError("");
    } catch (_) {
      showToast("Clipboard access denied — paste manually", "warning");
    }
  };

  // BUG-11 FIX: deactivate a terminal (requires confirmation)
  const handleDeactivate = async (terminalId) => {
    if (!window.confirm(
      `Deactivate terminal ${terminalId}?\n\nThis will immediately prevent the terminal from authenticating requests. ` +
      `Any ongoing voter sessions at this terminal will be rejected. This action can be reversed by re-provisioning the terminal.`
    )) return;
    setDeactivating(terminalId);
    try {
      await deactivateTerminal(terminalId);
      showToast(`Terminal ${terminalId} deactivated`, "warning");
      load();
    } catch (e) {
      showToast(`Failed to deactivate ${terminalId}: ` + (e.response?.data?.error || e.message), "error");
    } finally {
      setDeactivating(null);
    }
  };

  // Terminals with no officer PIN — for the warning banner
  const unprovisioned = registry.filter(t => t.active && !t.pinProvisioned);

  return (
    <>
      <Toast msg={toast.msg} type={toast.type} onClose={() => setToast({ msg:"", type:"success" })} />

      {/* Officer PIN modal */}
      {pinModal && (
        <SetOfficerPinModal
          terminal={pinModal}
          onClose={() => setPinModal(null)}
          onSuccess={() => {
            showToast(`Officer PIN ${pinModal.pinProvisioned ? "rotated" : "set"} for ${pinModal.terminalId}. Record and seal the PIN before election day.`);
            load();
          }}
        />
      )}

      {/* ── Unprovisioned PIN warning banner ──────────────────────────────── */}
      {isSuperAdmin && unprovisioned.length > 0 && (
        <div className="flex items-start gap-3 bg-amber-500/10 border border-amber-500/30
                        rounded-xl px-5 py-4 animate-fade-up">
          <Ic n="warning" s={16} c="#FCD34D" className="mt-0.5 flex-shrink-0" />
          <div className="flex-1">
            <p className="text-sm font-bold text-amber-300">
              {unprovisioned.length} terminal{unprovisioned.length > 1 ? "s" : ""} without an Officer PIN
            </p>
            <p className="text-xs text-amber-200/80 mt-0.5 leading-relaxed">
              {unprovisioned.map(t => t.terminalId).join(", ")} —
              these terminals will refuse to enter Enrollment, Voting, or Settings mode
              until an Officer PIN is set and fetched. Set PINs before election day.
            </p>
          </div>
        </div>
      )}

      <div className="c-card p-6">
        <SectionHeader
          title="Terminal Registry"
          sub="ECDSA P-256 public keys and Officer PIN provisioning for registered terminals."
          action={
            isSuperAdmin && (
              <button className="btn btn-purple btn-sm" onClick={() => openProvision()}>
                <Ic n="plus" s={13} /> Provision Terminal
              </button>
            )
          }
        />

        {/* How it works info box */}
        <div className="bg-purple-500/8 border border-purple-500/20 rounded-xl p-4 mb-5 flex gap-3">
          <Ic n="shield" s={16} c="#A78BFA" className="mt-0.5 flex-shrink-0" />
          <div className="text-xs text-sub leading-relaxed space-y-1.5">
            <p>
              <span className="font-bold text-purple-300">ECDSA Signing:</span>{" "}
              Each ESP32-S3 generates an ECDSA P-256 keypair on first boot. The public key is
              pasted here. Every terminal request is signed and verified against this key.
            </p>
            <p>
              <span className="font-bold text-amber-300">Officer PIN:</span>{" "}
              A 6-digit PIN set here (hashed with SHA-256, plain PIN discarded) is fetched by
              the terminal on boot. It guards Enrollment, Voting, and Settings mode.
              Communicate the plain PIN to the Returning Officer via sealed envelope.
            </p>
          </div>
        </div>

        {/* Provision form */}
        {showForm && (
          <div className="bg-card border border-border-hi rounded-2xl p-6 mb-5 animate-fade-up space-y-4">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-bold text-ink">
                {rotateId ? `Rotate Key — ${rotateId}` : "Provision New Terminal"}
              </h3>
              <button onClick={() => setShowForm(false)} className="text-muted hover:text-sub">
                <Ic n="close" s={14} />
              </button>
            </div>

            {formError && (
              <div className="flex items-start gap-2 bg-red-500/10 border border-red-500/25 rounded-xl px-4 py-3">
                <Ic n="warning" s={14} c="#F87171" className="mt-0.5 flex-shrink-0" />
                <span className="text-sm text-red-300">{formError}</span>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <Label>Terminal ID <span className="text-danger">*</span></Label>
                <input
                  className="inp inp-md mono"
                  placeholder="TERM-KD-001"
                  value={form.terminalId}
                  onChange={e => setForm(p => ({ ...p, terminalId: e.target.value }))}
                  disabled={!!rotateId}
                />
                <p className="text-[11px] text-muted mt-1">
                  Must match <span className="mono text-purple-300">TERMINAL_ID</span> in firmware
                </p>
              </div>
              <div>
                <Label>Label / Location</Label>
                <input
                  className="inp inp-md"
                  placeholder="Kaduna North Ward 3, Unit 7"
                  value={form.label}
                  onChange={e => setForm(p => ({ ...p, label: e.target.value }))}
                />
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <Label>ECDSA Public Key (Base64 SPKI) <span className="text-danger">*</span></Label>
                <button
                  onClick={pasteFromClipboard}
                  className="text-[11px] font-semibold text-purple-400 hover:text-purple-300 flex items-center gap-1">
                  <Ic n="database" s={11} /> Paste from clipboard
                </button>
              </div>
              <textarea
                ref={pubKeyRef}
                className="inp font-mono text-[11px] leading-relaxed h-24 resize-none"
                placeholder={"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...\n\nCopy from Serial Monitor output.\nLook for [KEY] ─── PUBLIC KEY ───"}
                value={form.publicKey}
                onChange={e => setForm(p => ({ ...p, publicKey: e.target.value }))}
              />
              {form.publicKey && (
                <p className={`text-[11px] mt-1 font-bold
                  ${form.publicKey.trim().length >= 80 ? "text-success" : "text-warning"}`}>
                  {form.publicKey.trim().length} chars {form.publicKey.trim().length >= 80 ? "✓" : "(too short?)"}
                </p>
              )}
            </div>

            <div className="md:w-48">
              <Label>Polling Unit ID</Label>
              <input
                className="inp inp-md"
                type="number"
                placeholder="42 (optional)"
                value={form.pollingUnitId}
                onChange={e => setForm(p => ({ ...p, pollingUnitId: e.target.value }))}
              />
            </div>

            {!rotateId && (
              <div className="flex items-start gap-2 bg-amber-500/10 border border-amber-500/25 rounded-xl px-4 py-3">
                <Ic n="warning" s={14} c="#FCD34D" className="mt-0.5 flex-shrink-0" />
                <p className="text-xs text-amber-200">
                  After provisioning, remember to{" "}
                  <span className="font-bold">set an Officer PIN</span> for this terminal
                  from the registry table. The terminal cannot enter protected modes without it.
                </p>
              </div>
            )}

            <div className="flex items-center gap-3 pt-1">
              <button className="btn btn-purple btn-sm" onClick={handleSubmit} disabled={saving}>
                {saving ? <Spinner s={13} /> : <Ic n={rotateId ? "refresh" : "check"} s={13} />}
                {saving ? "Saving…" : rotateId ? "Rotate Key" : "Provision Terminal"}
              </button>
              <button className="btn btn-surface btn-sm" onClick={() => setShowForm(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}

        {/* Registry table */}
        {loading ? (
          <div className="flex justify-center py-12"><Spinner s={28} /></div>
        ) : registry.length === 0 ? (
          <EmptyState
            icon="chip"
            title="No terminals provisioned"
            sub={isSuperAdmin
              ? "Click 'Provision Terminal' to register the first ESP32-S3 terminal."
              : "No terminals have been provisioned yet."}
          />
        ) : (
          <>
            {/* Table header */}
            <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
              style={{ gridTemplateColumns:"130px 1fr 80px 110px 110px 120px auto" }}>
              {["Terminal ID","Label / Location","Unit","Registered By","Last Seen","Officer PIN","Actions"].map(h => (
                <span key={h} className="sect-lbl">{h}</span>
              ))}
            </div>
            <hr className="divider mb-1" />

            {registry.map((t, i) => (
              <div key={t.terminalId}
                className={`trow animate-fade-up ${!t.active ? "opacity-50" : ""}`}
                style={{ gridTemplateColumns:"130px 1fr 80px 110px 110px 120px auto", gap:"12px", animationDelay:`${i*20}ms` }}>

                <div>
                  <span className="mono text-[12px] font-bold text-purple-400">{t.terminalId}</span>
                  {!t.active && <span className="badge badge-grey text-[9px] ml-1">INACTIVE</span>}
                </div>

                <span className="text-[12px] text-sub truncate">{t.label || "—"}</span>
                <span className="mono text-[11px] text-muted">{t.pollingUnitId || "—"}</span>
                <span className="text-[11px] text-sub">{t.registeredBy || "—"}</span>

                <span className="mono text-[11px] text-muted">
                  {t.lastSeen
                    ? new Date(t.lastSeen).toLocaleDateString("en-NG", { day:"2-digit", month:"short", hour:"2-digit", minute:"2-digit" })
                    : "Never"}
                </span>

                {/* ── Officer PIN status ────────────────────────────────── */}
                <div>
                  {t.pinProvisioned
                    ? (
                      <span className="flex items-center gap-1.5 text-[11px] font-semibold text-success">
                        <Ic n="shield" s={11} /> PIN Set
                      </span>
                    ) : (
                      <span className="flex items-center gap-1.5 text-[11px] font-semibold text-amber-400
                                       animate-pulse">
                        <Ic n="warning" s={11} /> No PIN
                      </span>
                    )}
                </div>

                {/* ── Actions ───────────────────────────────────────────── */}
                <div className="flex gap-1.5 flex-wrap">
                  {isSuperAdmin && (
                    <>
                      <button
                        className="btn btn-surface btn-sm !text-[11px]"
                        aria-label={`Rotate ECDSA signing key for ${t.terminalId}`}
                        title="Rotate ECDSA signing key"
                        onClick={() => openProvision(t)}>
                        <Ic n="refresh" s={11} /> Rotate Key
                      </button>

                      <button
                        className={`btn btn-sm !text-[11px]
                          ${t.pinProvisioned
                            ? "btn-surface"
                            : "btn-warning border border-amber-500/40 text-amber-300 hover:bg-amber-500/15"}`}
                        aria-label={t.pinProvisioned ? `Rotate officer PIN for ${t.terminalId}` : `Set officer PIN for ${t.terminalId}`}
                        title={t.pinProvisioned ? "Rotate officer PIN" : "Set officer PIN"}
                        onClick={() => setPinModal(t)}>
                        <Ic n="shield" s={11} />
                        {t.pinProvisioned ? "Rotate PIN" : "Set PIN"}
                      </button>

                      {/* BUG-11 FIX: deactivate button — only shown for active terminals */}
                      {t.active && (
                        <button
                          className="btn btn-sm !text-[11px] btn-danger"
                          aria-label={`Deactivate terminal ${t.terminalId}`}
                          title="Immediately revoke terminal access"
                          onClick={() => handleDeactivate(t.terminalId)}
                          disabled={deactivating === t.terminalId}>
                          {deactivating === t.terminalId
                            ? <><Spinner s={11} /> Deactivating…</>
                            : <><Ic n="warning" s={11} /> Deactivate</>}
                        </button>
                      )}
                    </>
                  )}
                </div>
              </div>
            ))}
          </>
        )}
      </div>

      {/* Footer info */}
      <div className="c-card p-4">
        <p className="text-xs font-bold text-sub uppercase tracking-wide mb-2">
          Security Architecture
        </p>
        <p className="text-xs text-muted leading-relaxed">
          The backend never stores or transmits private keys or plain PINs. The ECDSA private key
          lives in the terminal's NVS partition. The officer PIN is hashed with SHA-256 on the
          backend immediately on receipt — the hash is stored in{" "}
          <span className="mono text-purple-300">terminal_registry.officer_pin_hash</span> and
          fetched by the terminal over its ECDSA-authenticated channel on first boot.
          The hash is not returned to this dashboard — only a{" "}
          <span className="mono text-purple-300">pinProvisioned: boolean</span> indicator.
        </p>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  ROOT
// ─────────────────────────────────────────────────────────────────────────────

export default function TerminalsView() {
  const [tab, setTab] = useState("monitoring");

  return (
    <div className="p-7 flex flex-col gap-5">
      <div className="flex items-center gap-2">
        <Tab id="monitoring" active={tab === "monitoring"} onClick={setTab}>
          <Ic n="chip" s={13} className="mr-1.5" /> Live Monitoring
        </Tab>
        <Tab id="registry" active={tab === "registry"} onClick={setTab}>
          <Ic n="shield" s={13} className="mr-1.5" /> Terminal Registry
        </Tab>
      </div>

      {tab === "monitoring" && <MonitoringTab />}
      {tab === "registry"   && <RegistryTab />}
    </div>
  );
}
