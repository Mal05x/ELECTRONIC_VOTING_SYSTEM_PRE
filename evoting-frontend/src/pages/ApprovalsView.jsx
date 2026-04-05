/**
 * ApprovalsView — pending multi-signature state changes.
 * SUPER_ADMIN only. Shows pending actions and allows signing or cancelling.
 */
import { useState, useEffect, useCallback } from "react";
import { getPendingChanges, signStateChange, cancelStateChange } from "../api/multisig.js";
import { useKeypair } from "../context/KeypairContext.jsx";
import { SectionHeader, Spinner, Ic, StatusBadge } from "../components/ui.jsx";

const ACTION_LABELS = {
  ACTIVATE_ELECTION:   { label: "Activate Election",    color: "text-green-400",  icon: "ballot"  },
  CLOSE_ELECTION:      { label: "Close Election",       color: "text-red-400",    icon: "ballot"  },
  BULK_UNLOCK_CARDS:   { label: "Bulk Unlock Cards",    color: "text-orange-400", icon: "unlock"  },
  DEACTIVATE_ADMIN:    { label: "Deactivate Admin",     color: "text-red-400",    icon: "lock"    },
  ACTIVATE_ADMIN:      { label: "Reactivate Admin",     color: "text-green-400",  icon: "check"   },
  PUBLISH_MERKLE_ROOT: { label: "Publish Merkle Root",  color: "text-purple-400", icon: "shield"  },
};

function TimeLeft({ expiresAt }) {
  const diff = new Date(expiresAt) - Date.now();
  if (diff <= 0) return <span className="text-danger text-xs">Expired</span>;
  const hrs = Math.floor(diff / 3600000);
  const mins = Math.floor((diff % 3600000) / 60000);
  const color = hrs < 2 ? "text-warning" : "text-muted";
  return <span className={`text-xs mono ${color}`}>{hrs}h {mins}m left</span>;
}

function SignatureBar({ received, required }) {
  const pct = Math.min(100, (received / required) * 100);
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 bg-elevated rounded-full overflow-hidden">
        <div className="h-full rounded-full bg-purple-500 transition-all duration-500"
          style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs mono text-sub whitespace-nowrap">
        {received}/{required} signatures
      </span>
    </div>
  );
}

export default function ApprovalsView() {
  const { signChallenge, hasLocalKey, needsSetup } = useKeypair();
  const [changes,   setChanges]   = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [signing,   setSigning]   = useState(null);
  const [cancelling,setCancelling]= useState(null);
  const [toast,     setToast]     = useState({ msg: "", type: "success" });

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4000);
  };

  const load = useCallback(() => {
    setLoading(true);
    getPendingChanges()
      .then(d => setChanges(d.pending || []))
      .catch(() => setChanges([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleSign = async (change) => {
    if (!hasLocalKey) {
      showToast("No signing key found. Please set up your keypair first.", "error");
      return;
    }
    setSigning(change.changeId);
    try {
      const sig = await signChallenge(change.changeId);
      if (!sig) { showToast("Signing failed — key may be corrupted", "error"); return; }

      const res = await signStateChange(change.changeId, sig);
      if (res.executed) {
        showToast(`✓ Threshold reached — ${change.actionType.replace(/_/g, " ")} executed`);
      } else {
        showToast("Signature recorded. Waiting for co-signature.");
      }
      load();
    } catch (e) {
      if (e.response?.status === 409) {
        // 409 = already signed — not an error, just reload
        showToast("Already signed — waiting for co-signature.");
        load();
      } else {
        showToast(e.response?.data?.error || e.message || "Sign failed", "error");
      }
    } finally { setSigning(null); }
  };

  const handleCancel = async (change) => {
    setCancelling(change.changeId);
    try {
      await cancelStateChange(change.changeId, "Cancelled by admin");
      showToast("State change cancelled");
      load();
    } catch (e) {
      showToast(e.response?.data?.error || "Cancel failed", "error");
    } finally { setCancelling(null); }
  };

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Toast */}
      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                         rounded-2xl border bg-card shadow-card text-sm font-semibold
                         animate-slide-in ${
                           toast.type === "error"
                             ? "border-red-500/30 text-danger"
                             : "border-purple-500/30 text-purple-300"
                         }`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15} />
          {toast.msg}
          <button onClick={() => setToast({ msg: "", type: "success" })}
            className="ml-1 text-muted hover:text-sub">
            <Ic n="close" s={12} />
          </button>
        </div>
      )}

      <SectionHeader
        title="Pending Approvals"
        sub="Actions requiring multi-signature authorisation before execution" />

      {/* Key status warning */}
      {needsSetup && (
        <div className="c-card p-4 border border-orange-500/30 bg-orange-500/5
                        flex items-center gap-3">
          <Ic n="warning" s={18} c="#fb923c" />
          <div className="flex-1">
            <div className="text-sm font-bold text-orange-300">Signing Key Required</div>
            <div className="text-xs text-muted mt-0.5">
              You need to set up your cryptographic signing key before you can approve actions.
              Go to Settings → Security to generate your key.
            </div>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20"><Spinner s={32} /></div>
      ) : changes.length === 0 ? (
        <div className="c-card p-12 flex flex-col items-center gap-3">
          <div className="w-14 h-14 rounded-2xl bg-green-500/10 border border-green-500/20
                          flex items-center justify-center">
            <Ic n="check" s={24} c="#34D399" />
          </div>
          <div className="text-sm font-bold text-ink">No Pending Approvals</div>
          <div className="text-xs text-muted">All sensitive actions are up to date</div>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {changes.map(c => {
            const meta = ACTION_LABELS[c.actionType] || { label: c.actionType, color: "text-sub", icon: "shield" };
            return (
              <div key={c.changeId}
                className="c-card p-5 flex flex-col gap-4 animate-fade-up
                           border border-border hover:border-purple-500/20 transition-colors">

                {/* Header */}
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-elevated border border-border
                                    flex items-center justify-center flex-shrink-0">
                      <Ic n={meta.icon} s={16} c="#8B7FA8" />
                    </div>
                    <div>
                      <div className={`text-sm font-bold ${meta.color}`}>{meta.label}</div>
                      <div className="text-xs text-muted mono mt-0.5 truncate max-w-[280px]">
                        {c.targetLabel || c.targetId}
                      </div>
                    </div>
                  </div>
                  <TimeLeft expiresAt={c.expiresAt} />
                </div>

                {/* Signature progress */}
                <SignatureBar received={c.received} required={c.required} />

                {/* Signers */}
                {c.signatures?.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {c.signatures.map((s, i) => (
                      <div key={i}
                        className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg
                                   bg-green-500/10 border border-green-500/20 text-xs
                                   text-green-300 font-semibold">
                        <Ic n="check" s={10} c="#34D399" sw={2.5} />
                        {s.username}
                      </div>
                    ))}
                  </div>
                )}

                {/* Actions */}
                <div className="flex gap-2 pt-1 border-t border-border">
                  {c.canSign && (
                    <button
                      className="btn btn-primary btn-sm flex-1 justify-center gap-2"
                      onClick={() => handleSign(c)}
                      disabled={signing === c.changeId || !hasLocalKey}>
                      {signing === c.changeId
                        ? <Spinner s={13} />
                        : <Ic n="shield" s={13} c="#fff" />}
                      Sign & Approve
                    </button>
                  )}
                  {c.iSigned && !c.executed && (
                    <div className="flex-1 flex items-center justify-center gap-2
                                    text-xs text-green-400 font-semibold">
                      <Ic n="check" s={12} c="#34D399" />
                      You signed this
                    </div>
                  )}
                  {c.executed && (
                    <div className="flex-1 flex items-center justify-center gap-2
                                    text-xs text-muted font-semibold">
                      <Ic n="check" s={12} c="#4A4464" />
                      Executed
                    </div>
                  )}
                  {(c.initiatedByMe || !c.iSigned) && !c.executed && (
                    <button
                      className="btn btn-sm border border-red-500/30 text-danger
                                 bg-red-500/8 hover:bg-red-500/15 rounded-xl
                                 px-3 py-1.5 text-xs font-semibold"
                      onClick={() => handleCancel(c)}
                      disabled={cancelling === c.changeId}>
                      {cancelling === c.changeId ? <Spinner s={12} /> : "Cancel"}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="c-card p-4 flex items-center gap-3">
        <Ic n="warning" s={15} c="#6B7280" />
        <p className="text-xs text-muted leading-relaxed">
          Pending approvals expire after 24 hours. All signatures and executions
          are permanently recorded in the audit log.
        </p>
      </div>
    </div>
  );
}
