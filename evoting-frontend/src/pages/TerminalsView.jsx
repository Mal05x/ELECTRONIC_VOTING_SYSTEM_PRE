import { useState, useEffect } from "react";
import { getTerminals, resolveTamperAlert } from "../api/terminals.js";
import { StatCard, StatusBadge, SectionHeader, EmptyState, Spinner } from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";
import client from "../api/client.js"; // <--- ADDED THIS IMPORT!

export default function TerminalsView() {
  const [terminals, setTerminals] = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [resolving, setResolving] = useState(null);
  const [toast,     setToast]     = useState({ msg: "", type: "success" });

  // Provisioning Modal State
  const [showModal, setShowModal] = useState(false);
  const [provData, setProvData]   = useState({ terminalId: "", publicKey: "", label: "", pollingUnitId: "" });
  const [provisioning, setProvisioning] = useState(false);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  const load = async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const data = await getTerminals();
      const mapped = data.map(t => ({
        id:        t.terminalId,
        battery:   t.batteryLevel ?? 0,
        status:    "ONLINE",
        tamper:    t.tamperFlag || false,
        ip:        t.ipAddress || "—",
        lastSeen:  t.reportedAt
          ? new Date(t.reportedAt).toLocaleTimeString("en-NG", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
          : "Never",
      }));
      setTerminals(mapped);
    } catch (e) {
      console.error("Failed to fetch terminals:", e);
      if (!silent) setTerminals([]);
    } finally {
      if (!silent) setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const interval = setInterval(() => load(true), 10_000);
    return () => clearInterval(interval);
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

  const handleProvision = async (e) => {
    e.preventDefault();
    setProvisioning(true);
    try {
      await client.post('/admin/terminals/provision', provData);
      showToast(`Successfully provisioned terminal: ${provData.terminalId}`);
      setShowModal(false);
      setProvData({ terminalId: "", publicKey: "", label: "", pollingUnitId: "" });
      load(true);
    } catch (error) {
      showToast(error.response?.data?.error || "Provisioning failed", "error");
    } finally {
      setProvisioning(false);
    }
  };

  const counts = {
    online:   terminals.filter(t => t.status === "ONLINE").length,
    offline:  terminals.filter(t => t.status === "OFFLINE").length,
    tampered: terminals.filter(t => t.tamper).length,
    lowBat:   terminals.filter(t => t.battery > 0 && t.battery <= 20).length,
  };

  const statusColor = s => ({
    ONLINE: "text-success", WARNING: "text-warning",
    ALERT: "text-danger", OFFLINE: "text-muted",
  }[s] || "text-muted");

  return (
    <div className="p-7 flex flex-col gap-5">

      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                         px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                         ${toast.type === "error" ? "text-danger" : "text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15} />
          {toast.msg}
          <button onClick={() => setToast({ msg: "", type: "success" })} className="text-muted hover:text-sub ml-1">
            <Ic n="close" s={12} />
          </button>
        </div>
      )}

      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard label="Online"           value={counts.online}   icon="chip"    accent="green"  delay={0} />
        <StatCard label="Offline"          value={counts.offline}  icon="warning" accent="amber"  delay={50} />
        <StatCard label="Low Battery"      value={counts.lowBat}   icon="trend"   accent="blue"   delay={100} />
        <StatCard label="Tamper Alerts"    value={counts.tampered} icon="shield"  accent="red"    delay={150} />
      </div>

      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "200ms" }}>
        <SectionHeader
          title="Hardware Fleet Monitoring"
          sub="ESP32-S3 Terminal Heartbeats & Security Status — updates every 10s"
          action={
            <div className="flex items-center gap-3">
                <button
                  className="btn btn-primary btn-sm gap-2"
                  onClick={() => setShowModal(true)}>
                  <Ic n="plus" s={13} c="#fff" /> Provision Terminal
                </button>

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
          style={{ gridTemplateColumns: "120px 100px 120px 100px 140px 1fr" }}>
          {["Terminal ID", "Battery", "Status", "Tamper", "Last Heartbeat", "Action"].map(h => (
            <span key={h} className="sect-lbl">{h}</span>
          ))}
        </div>
        <hr className="divider mb-1" />

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28} /></div>
        ) : terminals.length === 0 ? (
          <EmptyState
            icon="chip"
            title="No terminals registered"
            sub="Terminals appear here after their first heartbeat to POST /api/terminal/heartbeat"
          />
        ) : (
          terminals.map((t, i) => (
            <div key={t.id}
              className={`trow animate-fade-up ${t.tamper ? "bg-red-500/5 border-l-2 border-l-danger" : ""}`}
              style={{ gridTemplateColumns: "120px 100px 120px 100px 140px 1fr", gap: "12px", animationDelay: `${i * 20}ms` }}>

              <span className="mono text-[12px] font-bold text-purple-400">{t.id}</span>

              <div className="flex items-center gap-2">
                <div className="w-7 h-3.5 border border-sub/60 rounded-[3px] p-px relative bg-black/20 flex-shrink-0">
                  <div className={`h-full rounded-[2px] transition-all ${t.battery > 20 ? "bg-success" : "bg-danger"}`}
                    style={{ width: `${Math.max(t.battery, 0)}%` }} />
                </div>
                <span className="mono text-[11px] text-sub">{t.battery}%</span>
              </div>

              <span className={`text-[11px] font-bold ${statusColor(t.status)}`}>
                ● {t.status}
              </span>

              <span>
                {t.tamper
                  ? <span className="badge badge-red text-[9px]">⚑ ALERT</span>
                  : <span className="badge badge-grey text-[9px]">✓ Clear</span>}
              </span>

              <span className="mono text-[11px] text-muted">{t.lastSeen}</span>

              <div className="flex gap-1.5">
                {t.tamper && (
                  <button
                    className="btn btn-danger btn-sm !text-[11px]"
                    onClick={() => resolveTamper(t.id)}
                    disabled={resolving === t.id}>
                    {resolving === t.id ? <Spinner s={11} /> : <Ic n="shield" s={11} />}
                    Clear Alert
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </div> {/* <--- CLOSES THE .c-card DIV */}

      {/* PROVISIONING MODAL */}
      {showModal && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 backdrop-blur-sm animate-fade-in">
          <div className="bg-card w-full max-w-md rounded-2xl border border-border shadow-2xl overflow-hidden">
            <div className="p-5 border-b border-border flex justify-between items-center bg-elevated">
              <h3 className="font-bold text-ink">Provision New Terminal</h3>
              <button onClick={() => setShowModal(false)} className="text-muted hover:text-sub"><Ic n="close" s={16} /></button>
            </div>
            <form onSubmit={handleProvision} className="p-5 flex flex-col gap-4">
              <div>
                <label className="block text-xs font-semibold text-sub mb-1 uppercase">Terminal ID</label>
                <input required className="inp inp-md" placeholder="e.g., TERM-KD-001"
                  value={provData.terminalId} onChange={e => setProvData({...provData, terminalId: e.target.value})} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-sub mb-1 uppercase">ECDSA Public Key (Base64)</label>
                <textarea required className="inp inp-md h-24 font-mono text-xs resize-none" placeholder="Paste the key output from the ESP32 serial monitor..."
                  value={provData.publicKey} onChange={e => setProvData({...provData, publicKey: e.target.value})} />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-sub mb-1 uppercase">Label (Optional)</label>
                  <input className="inp inp-md" placeholder="e.g., Ward 3"
                    value={provData.label} onChange={e => setProvData({...provData, label: e.target.value})} />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-sub mb-1 uppercase">PU ID (Optional)</label>
                  <input type="number" className="inp inp-md" placeholder="42"
                    value={provData.pollingUnitId} onChange={e => setProvData({...provData, pollingUnitId: e.target.value})} />
                </div>
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" className="btn btn-surface btn-md" onClick={() => setShowModal(false)}>Cancel</button>
                <button type="submit" className="btn btn-primary btn-md" disabled={provisioning}>
                  {provisioning ? <Spinner s={16} /> : "Save to Registry"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div> /* <--- CLOSES THE MAIN PAGE CONTAINER (Only ONE of these now!) */
  );
}