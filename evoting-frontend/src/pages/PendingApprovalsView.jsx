/**
 * PendingApprovalsView — SUPER_ADMIN co-signature dashboard.
 * Shows all pending state changes requiring cryptographic approval.
 * Uses Web Crypto API to sign the change UUID with the stored ECDSA private key.
 */
import { useState, useEffect, useCallback } from "react";
import { Ic, Spinner, SectionHeader, EmptyState } from "../components/ui.jsx";
import { getPendingStateChanges, signStateChange, cancelStateChange } from "../api/multisig.js";
import { signPayload, hasStoredKeypair } from "../api/webcrypto.js";

const ACTION_LABELS = {
  ACTIVATE_ELECTION:   { label: "Activate Election",   color: "text-green-400",  bg: "bg-green-500/10",  border: "border-green-500/20",  icon: "ballot"  },
  CLOSE_ELECTION:      { label: "Close Election",       color: "text-red-400",    bg: "bg-red-500/10",    border: "border-red-500/20",    icon: "lock"    },
  BULK_UNLOCK_CARDS:   { label: "Bulk Unlock Cards",    color: "text-orange-400", bg: "bg-orange-500/10", border: "border-orange-500/20", icon: "unlock"  },
  DEACTIVATE_ADMIN:    { label: "Deactivate Admin",     color: "text-red-400",    bg: "bg-red-500/10",    border: "border-red-500/20",    icon: "warning" },
  ACTIVATE_ADMIN:      { label: "Reactivate Admin",     color: "text-blue-400",   bg: "bg-blue-500/10",   border: "border-blue-500/20",   icon: "check"   },
  PUBLISH_MERKLE_ROOT: { label: "Publish Merkle Root",  color: "text-purple-400", bg: "bg-purple-500/10", border: "border-purple-500/20", icon: "shield"  },
};

function timeLeft(expiresAt) {
  const diff = new Date(expiresAt) - Date.now();
  if (diff <= 0) return "Expired";
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  return `${h}h ${m}m remaining`;
}

export default function PendingApprovalsView() {
  const [changes,    setChanges]    = useState([]);
  const [loading,    setLoading]    = useState(true);
  const [signing,    setSigning]    = useState(null);
  const [cancelling, setCancelling] = useState(null);
  const [toast,      setToast]      = useState({ msg: "", type: "success" });
  const [noKey,      setNoKey]      = useState(false);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4000);
  };

  const load = useCallback(() => {
    setLoading(true);
    if (!hasStoredKeypair()) { setNoKey(true); setLoading(false); return; }
    getPendingStateChanges()
      .then(data => setChanges(data.pending || []))
      .catch(() => setChanges([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleSign = async (change) => {
    setSigning(change.changeId);
    try {
      // Sign the change UUID — backend verifies this against registered public key
      const sig = await signPayload(change.changeId);
      const res = await signStateChange(change.changeId, sig);
      showToast(res.executed
        ? `✓ Action executed: ${ACTION_LABELS[change.actionType]?.label || change.actionType}`
        : "Signature recorded. Waiting for remaining co-signatures.");
      load();
    } catch (e) {
      showToast(e.response?.data?.error || e.message || "Signing failed", "error");
    } finally { setSigning(null); }
  };

  const handleCancel = async (changeId) => {
    setCancelling(changeId);
    try {
      await cancelStateChange(changeId, "Cancelled by admin");
      showToast("State change cancelled");
      load();
    } catch (e) {
      showToast(e.response?.data?.error || "Cancel failed", "error");
    } finally { setCancelling(null); }
  };

  const pendingCount   = changes.filter(c => !c.executed && !c.cancelled).length;
  const awaitingMySig  = changes.filter(c => c.canSign).length;

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Toast */}
      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                         rounded-2xl border bg-card shadow-card text-sm font-semibold animate-slide-in
                         ${toast.type === "error" ? "border-red-500/30 text-danger" : "border-purple-500/30 text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15} />
          {toast.msg}
          <button onClick={() => setToast({ msg: "", type: "success" })} className="ml-1 text-muted hover:text-sub">
            <Ic n="close" s={12} />
          </button>
        </div>
      )}

      <SectionHeader
        title="Pending Approvals"
        sub="State changes requiring cryptographic co-signature from SUPER_ADMINs" />

      {/* Stats */}
      <div className="grid grid-cols-2 gap-4">
        <div className="c-card p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-xl bg-orange-500/10 border border-orange-500/20
                          flex items-center justify-center flex-shrink-0">
            <Ic n="warning" s={18} c="#fb923c" />
          </div>
          <div>
            <div className="text-2xl font-extrabold text-ink">{pendingCount}</div>
            <div className="text-xs text-muted">Pending Changes</div>
          </div>
        </div>
        <div className="c-card p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-xl bg-purple-500/10 border border-purple-500/20
                          flex items-center justify-center flex-shrink-0">
            <Ic n="shield" s={18} c="#A78BFA" />
          </div>
          <div>
            <div className="text-2xl font-extrabold text-purple-400">{awaitingMySig}</div>
            <div className="text-xs text-muted">Awaiting My Signature</div>
          </div>
        </div>
      </div>

      {/* No keypair warning */}
      {noKey && (
        <div className="c-card p-6 border border-orange-500/20 bg-orange-500/5">
          <div className="flex items-start gap-3">
            <Ic n="warning" s={20} c="#fb923c" />
            <div>
              <div className="text-sm font-bold text-orange-300 mb-1">No Signing Key Found</div>
              <div className="text-xs text-sub leading-relaxed">
                Your ECDSA signing key is not in this browser. You cannot sign approvals without it.
                If you registered a key in a different browser, you need to generate a new one here and re-register it.
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Changes list */}
      {loading ? (
        <div className="flex justify-center py-16"><Spinner s={28} /></div>
      ) : changes.length === 0 ? (
        <EmptyState icon="check" title="No pending approvals"
          sub="All state changes have been executed or there are none initiated yet" />
      ) : (
        <div className="flex flex-col gap-4">
          {changes.map(change => {
            const meta = ACTION_LABELS[change.actionType] || { label: change.actionType, color: "text-sub", bg: "bg-elevated", border: "border-border", icon: "audit" };
            const expired = new Date(change.expiresAt) < Date.now();

            return (
              <div key={change.changeId}
                className={`c-card p-5 border ${meta.border} ${meta.bg} animate-fade-up`}>

                {/* Header row */}
                <div className="flex items-start justify-between gap-4 mb-4">
                  <div className="flex items-center gap-3">
                    <div className={`w-9 h-9 rounded-xl ${meta.bg} border ${meta.border}
                                    flex items-center justify-center flex-shrink-0`}>
                      <Ic n={meta.icon} s={16} c={meta.color.replace("text-", "#").replace("green-400","34D399").replace("red-400","F87171").replace("orange-400","fb923c").replace("purple-400","A78BFA").replace("blue-400","60A5FA")} />
                    </div>
                    <div>
                      <div className={`text-sm font-bold ${meta.color}`}>{meta.label}</div>
                      <div className="text-xs text-muted font-mono mt-0.5">{change.targetLabel || change.targetId}</div>
                    </div>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <div className={`text-xs font-semibold ${expired ? "text-danger" : "text-muted"}`}>
                      {expired ? "⚠ Expired" : timeLeft(change.expiresAt)}
                    </div>
                    {change.initiatedByMe && (
                      <div className="text-[10px] text-purple-400 mt-0.5">Initiated by you</div>
                    )}
                  </div>
                </div>

                {/* Signature progress */}
                <div className="mb-4">
                  <div className="flex items-center justify-between mb-1.5">
                    <span className="text-xs text-muted">Signatures</span>
                    <span className="mono text-xs font-bold text-ink">
                      {change.received} / {change.required}
                    </span>
                  </div>
                  <div className="h-1.5 bg-black/30 rounded-full overflow-hidden">
                    <div className="h-full rounded-full bg-purple-500 transition-all duration-500"
                      style={{ width: `${Math.min(100, (change.received / change.required) * 100)}%` }} />
                  </div>
                  {change.signatures?.length > 0 && (
                    <div className="flex flex-wrap gap-1.5 mt-2">
                      {change.signatures.map(s => (
                        <span key={s.adminId}
                          className="text-[10px] bg-green-500/10 border border-green-500/20
                                     text-green-400 px-2 py-0.5 rounded-full font-semibold">
                          ✓ {s.username}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                {/* Actions */}
                <div className="flex gap-2">
                  {change.canSign && !expired && (
                    <button
                      className="btn btn-sm btn-primary flex-1 justify-center gap-2"
                      onClick={() => handleSign(change)}
                      disabled={signing === change.changeId || noKey}>
                      {signing === change.changeId
                        ? <><Spinner s={13} /> Signing...</>
                        : <><Ic n="shield" s={13} c="#fff" /> Sign & Approve</>}
                    </button>
                  )}
                  {change.iSigned && !change.executed && (
                    <div className="flex-1 flex items-center justify-center gap-2
                                    bg-green-500/10 border border-green-500/20 rounded-xl
                                    text-xs font-semibold text-green-400 py-2">
                      <Ic n="check" s={13} c="#34D399" /> Signed by you
                    </div>
                  )}
                  {change.executed && (
                    <div className="flex-1 flex items-center justify-center gap-2
                                    bg-purple-500/10 border border-purple-500/20 rounded-xl
                                    text-xs font-semibold text-purple-400 py-2">
                      <Ic n="check" s={13} c="#A78BFA" /> Executed
                    </div>
                  )}
                  {(change.initiatedByMe || !change.iSigned) && !change.executed && (
                    <button
                      className="btn btn-sm btn-danger px-3"
                      onClick={() => handleCancel(change.changeId)}
                      disabled={cancelling === change.changeId}
                      title="Cancel this state change">
                      {cancelling === change.changeId ? <Spinner s={13} /> : <Ic n="close" s={13} />}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="flex justify-end">
        <button className="btn btn-surface btn-sm gap-1.5" onClick={load}>
          <Ic n="refresh" s={13} /> Refresh
        </button>
      </div>
    </div>
  );
}
