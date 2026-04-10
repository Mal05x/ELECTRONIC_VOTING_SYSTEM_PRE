import { useState, useEffect, useRef } from "react";
import { useAuth } from "../context/AuthContext.jsx";
import {
  getTerminals, resolveTamperAlert,
  getTerminalRegistry, provisionTerminal,
} from "../api/terminals.js";
import {
  StatCard, StatusBadge, SectionHeader, EmptyState, Spinner, Label,
} from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

// ── Small helpers ─────────────────────────────────────────────────────────────

function Toast({ msg, type, onClose }) {
  if (!msg) return null;
  return (
    <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                     px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                     ${type === "error" ? "text-danger" : type === "warning" ? "text-yellow-300" : "text-purple-300"}`}>
      <Ic n={type === "error" ? "warning" : type === "warning" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="text-muted hover:text-sub ml-1"><Ic n="close" s={12} /></button>
    </div>
  );
}

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

// ─────────────────────────────────────────────────────────────────────────────
//  TAB 1 — LIVE MONITORING (heartbeats)
// ─────────────────────────────────────────────────────────────────────────────

function MonitoringTab() {
  const [terminals, setTerminals] = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [resolving, setResolving] = useState(null);
  const [toast,     setToast]     = useState({ msg: "", type: "success" });

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  const load = async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const data = await getTerminals();
      const now  = Date.now();
      const mapped = data.map(t => {
        const lastMs  = t.reportedAt ? new Date(t.reportedAt).getTime() : 0;
        const ageSecs = (now - lastMs) / 1000;
        let status = "OFFLINE";
        if (lastMs > 0) {
          if (ageSecs < 300)  status = "ONLINE";
          else if (ageSecs < 900) status = "WARNING";
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
    } catch (e) {
      if (!silent) setTerminals([]);
    } finally {
      if (!silent) setLoading(false);
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

  const statusColor = s => ({ ONLINE:"text-success", WARNING:"text-warning", ALERT:"text-danger", OFFLINE:"text-muted" }[s] || "text-muted");

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
              <div className="flex items-center gap-2 text-xs font-bold text-success
                              bg-green-500/10 px-3 py-1.5 rounded-lg border border-green-500/20">
                <span className="live-dot" /> Live
              </div>
              <button className="btn btn-surface btn-sm" onClick={() => load(false)}>
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
//  TAB 2 — TERMINAL REGISTRY (provisioning)
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
  const [rotateId,    setRotateId]    = useState(null);   // terminal being key-rotated
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
      // Key rotation — pre-fill terminal ID, clear public key
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

    // Basic Base64 check
    if (!/^[A-Za-z0-9+/]+=*$/.test(form.publicKey.trim())) {
      setFormError("Public key must be a valid Base64 string (copy it exactly from Serial Monitor)");
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
        : `Terminal ${payload.terminalId} provisioned successfully`);
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
      showToast("Clipboard access denied — paste manually into the field", "warning");
    }
  };

  return (
    <>
      <Toast msg={toast.msg} type={toast.type} onClose={() => setToast({ msg:"", type:"success" })} />

      <div className="c-card p-6">
        <SectionHeader
          title="Terminal Registry"
          sub="ECDSA P-256 public keys for registered terminals. Application-layer request signing replaces mTLS on cloud deployments."
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
          <div className="text-xs text-sub leading-relaxed space-y-1">
            <p><span className="font-bold text-purple-300">How this works:</span> Each ESP32-S3 terminal generates an ECDSA P-256 keypair on first boot and stores the private key in NVS. The public key is printed to Serial Monitor. You paste it here. Every subsequent request from that terminal is signed with its private key and verified against this registered public key.</p>
            <p className="text-muted">To get a terminal's public key: flash the <span className="mono text-purple-300">evoting_signed_ping_test</span> sketch with <span className="mono text-purple-300">TEST_SIGNED_AUTH = false</span> and open Serial Monitor at 115200 baud.</p>
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
                <p className="text-[11px] text-muted mt-1">Must exactly match <span className="mono text-purple-300">TERMINAL_ID</span> in firmware</p>
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
                placeholder={"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...\n\nCopy this exactly from Serial Monitor output.\nLook for the line after [KEY] ─── PUBLIC KEY ───"}
                value={form.publicKey}
                onChange={e => setForm(p => ({ ...p, publicKey: e.target.value }))}
              />
              <p className="text-[11px] text-muted mt-1">
                Flash <span className="mono text-purple-300">evoting_signed_ping_test.ino</span> with <span className="mono text-purple-300">TEST_SIGNED_AUTH=false</span> → open Serial Monitor → copy the printed key.
                {form.publicKey && (
                  <span className={`ml-2 font-bold ${form.publicKey.trim().length >= 80 ? "text-success" : "text-warning"}`}>
                    {form.publicKey.trim().length} chars {form.publicKey.trim().length >= 80 ? "✓" : "(too short?)"}
                  </span>
                )}
              </p>
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

            {rotateId && (
              <div className="flex items-start gap-2 bg-amber-500/10 border border-amber-500/25 rounded-xl px-4 py-3">
                <Ic n="warning" s={14} c="#FCD34D" className="mt-0.5 flex-shrink-0" />
                <span className="text-xs text-amber-300">
                  Key rotation replaces the existing public key. The terminal must have already regenerated its NVS keypair (run clearNVS() in firmware) and the new public key must be pasted above.
                </span>
              </div>
            )}

            <div className="flex items-center gap-3 pt-1">
              <button
                className="btn btn-purple btn-sm"
                onClick={handleSubmit}
                disabled={saving}>
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
              ? "Click 'Provision Terminal' above to register the first ESP32-S3 terminal."
              : "No terminals have been provisioned yet. A SUPER_ADMIN must provision terminals."}
          />
        ) : (
          <>
            <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
              style={{ gridTemplateColumns:"130px 1fr 100px 120px 130px auto" }}>
              {["Terminal ID","Label / Location","Unit","Registered By","Last Seen","Actions"].map(h => (
                <span key={h} className="sect-lbl">{h}</span>
              ))}
            </div>
            <hr className="divider mb-1" />

            {registry.map((t, i) => (
              <div key={t.terminalId}
                className={`trow animate-fade-up ${!t.active ? "opacity-50" : ""}`}
                style={{ gridTemplateColumns:"130px 1fr 100px 120px 130px auto", gap:"12px", animationDelay:`${i*20}ms` }}>

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

                <div className="flex gap-1.5">
                  {isSuperAdmin && (
                    <button
                      className="btn btn-surface btn-sm !text-[11px]"
                      title="Rotate signing key"
                      onClick={() => openProvision(t)}>
                      <Ic n="refresh" s={11} /> Rotate Key
                    </button>
                  )}
                </div>
              </div>
            ))}
          </>
        )}
      </div>

      {/* Key column info */}
      <div className="c-card p-4">
        <p className="text-xs font-bold text-sub uppercase tracking-wide mb-2">Public Key Verification</p>
        <p className="text-xs text-muted leading-relaxed">
          The backend never stores or transmits private keys. Only the public key is registered here.
          The ESP32-S3 private key lives exclusively in its NVS partition.
          To verify a terminal's identity, the backend computes <span className="mono text-purple-300">SHA256(terminalId|timestamp|SHA256(body))</span>, then checks the ECDSA signature in the <span className="mono text-purple-300">X-Terminal-Signature</span> header against this registered public key.
        </p>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  ROOT — TerminalsView with tabs
// ─────────────────────────────────────────────────────────────────────────────

export default function TerminalsView() {
  const [tab, setTab] = useState("monitoring");

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Tab bar */}
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
