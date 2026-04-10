/**
 * RegistrationView — terminal-initiated voter registration.
 *
 * Two-panel layout:
 *   Left:  Pending cards — cards scanned by terminals awaiting demographics
 *   Right: Demographics form — fill in for selected pending card
 */
import { useState, useEffect, useCallback } from "react";
import { getPendingRegistrations, commitRegistration, cancelPendingRegistration } from "../api/registration.js";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { SectionHeader, Spinner, Ic } from "../components/ui.jsx";

function ToastBar({ msg, type, onClose }) {
  if (!msg) return null;
  const styles = {
    error:   "border-red-500/30 text-danger",
    warning: "border-yellow-500/30 text-yellow-300",
    success: "border-purple-500/30 text-purple-300",
  };
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                     rounded-2xl border bg-card shadow-card text-sm font-semibold
                     animate-slide-in ${styles[type] || styles.success}`}>
      <Ic n={type === "error" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="ml-1 text-muted hover:text-sub">
        <Ic n="close" s={12} />
      </button>
    </div>
  );
}

const GENDERS = ["MALE", "FEMALE", "OTHER"];

export default function RegistrationView() {
  const [pending,    setPending]    = useState([]);
  const [loading,    setLoading]    = useState(true);
  const [selected,   setSelected]   = useState(null);
  const [saving,     setSaving]     = useState(false);
  const [cancelling, setCancelling] = useState(null);
  const [toast,      setToast]      = useState({ msg: "", type: "success" });
  const [result,     setResult]     = useState(null);
  const [awaitingAuth, setAwaitingAuth] = useState(false);

  // Form fields
  const [firstName, setFirstName] = useState("");
  const [surname,   setSurname]   = useState("");
  const [dob,       setDob]       = useState("");
  const [gender,    setGender]    = useState("MALE");

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 5000);
  };

  const load = useCallback(() => {
    setLoading(true);
    getPendingRegistrations()
      .then(d => setPending(d.pending || []))
      .catch(() => setPending([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    // Auto-poll every 15 seconds so new cards from the terminal appear without manual refresh
    const iv = setInterval(() => load(), 15_000);
    return () => clearInterval(iv);
  }, [load]);

  const resetForm = () => {
    setFirstName(""); setSurname(""); setDob(""); setGender("MALE");
    setResult(null);
  };

  const selectCard = (p) => {
    setSelected(p);
    resetForm();
  };

  // Step-up auth wrapper for commit registration
  const { trigger: triggerCommit, modal: commitModal } = useStepUpAction(
    "COMMIT_REGISTRATION",
    () => `Register voter: card ${selected?.cardIdHash?.slice(0, 12) || ""}`,
    async (authHeaders) => {
      if (!selected) return;
      if (!firstName.trim() || !surname.trim() || !dob || !gender) {
        showToast("All fields are required", "error"); return;
      }
      if (!/^\d{4}-\d{2}-\d{2}$/.test(dob)) {
        showToast("Date of birth must be YYYY-MM-DD", "error"); return;
      }
      setSaving(true);
      try {
        const res = await commitRegistration(selected.pendingId, {
          firstName: firstName.trim(),
          surname:   surname.trim(),
          dateOfBirth: dob,
          gender,
        }, authHeaders);
        setResult(res);
        showToast(`Voter registered — ${res.votingId}`);
        load();
      } catch (e) {
        showToast(e.response?.data?.error || e.message || "Registration failed", "error");
      } finally { setSaving(false); }
    }
  );

  const handleCommit = () => {
    if (!selected) return;
    if (!firstName.trim() || !surname.trim() || !dob || !gender) {
      showToast("All fields are required", "error"); return;
    }
    triggerCommit();
  };

  const handleCancel = async (p) => {
    setCancelling(p.pendingId);
    try {
      await cancelPendingRegistration(p.pendingId);
      if (selected?.pendingId === p.pendingId) { setSelected(null); resetForm(); }
      load();
      showToast("Pending registration cancelled");
    } catch (e) {
      showToast("Cancel failed: " + (e.response?.data?.error || e.message), "error");
    } finally { setCancelling(null); }
  };

  const timeLeft = (expiresAt) => {
    const diff = new Date(expiresAt) - Date.now();
    if (diff <= 0) return "Expired";
    const hrs = Math.floor(diff / 3600000);
    const mins = Math.floor((diff % 3600000) / 60000);
    return `${hrs}h ${mins}m`;
  };

  return (
    <div className="p-7 flex flex-col gap-5">
      <ToastBar msg={toast.msg} type={toast.type}
        onClose={() => setToast({ msg: "", type: "success" })} />

      <SectionHeader
        title="Voter Registration"
        sub="Terminal scans the JCOP4 card first — then fill demographics here to complete registration" />

      {/* How it works */}
      <div className="c-card p-4 flex items-start gap-3 border border-purple-500/15">
        <Ic n="warning" s={16} c="#A78BFA" />
        <div className="text-xs text-sub leading-relaxed">
          <strong className="text-ink">How it works:</strong> Ask the voter to tap their
          smart card on the terminal. The terminal reads the card and creates a pending
          record below. Select it, fill in the voter's details, and click Register.
          The physical card must be present — registration cannot be completed without it.
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[1fr_420px] gap-5">

        {/* Left: pending cards */}
        <div className="c-card p-5 flex flex-col gap-3">
          <div className="flex items-center justify-between">
            <div className="text-sm font-bold text-ink">
              Cards Awaiting Demographics
              {pending.length > 0 && (
                <span className="ml-2 badge badge-purple">{pending.length}</span>
              )}
            </div>
            <button className="btn btn-ghost btn-sm gap-1.5" onClick={load}>
              <Ic n="refresh" s={13} /> Refresh
            </button>
          </div>

          {loading ? (
            <div className="flex justify-center py-10"><Spinner s={24} /></div>
          ) : pending.length === 0 ? (
            <div className="flex flex-col items-center py-10 gap-2">
              <Ic n="chip" s={28} c="#2A2A4A" />
              <div className="text-xs text-muted text-center">
                No cards waiting.<br/>Ask the voter to tap their card on the terminal.
              </div>
            </div>
          ) : (
            pending.map(p => (
              <button key={p.pendingId}
                className={`w-full text-left p-4 rounded-xl border transition-all
                            ${selected?.pendingId === p.pendingId
                              ? "border-purple-500/50 bg-purple-500/8"
                              : "border-border hover:border-purple-500/20 bg-elevated"}`}
                onClick={() => selectCard(p)}>
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-lg bg-purple-500/15 border
                                    border-purple-500/25 flex items-center justify-center
                                    flex-shrink-0">
                      <Ic n="voters" s={14} c="#A78BFA" />
                    </div>
                    <div>
                      <div className="text-xs font-bold text-ink mono truncate max-w-[180px]">
                        {p.cardIdHash}
                      </div>
                      <div className="text-[10px] text-muted mt-0.5">
                        Terminal: {p.terminalId}
                      </div>
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-1 flex-shrink-0">
                    <span className="text-[10px] text-muted mono">
                      {timeLeft(p.expiresAt)}
                    </span>
                    <button
                      className="text-[10px] text-danger hover:text-red-300
                                 transition-colors font-semibold"
                      onClick={e => { e.stopPropagation(); handleCancel(p); }}
                      disabled={cancelling === p.pendingId}>
                      {cancelling === p.pendingId ? <Spinner s={10} /> : "Cancel"}
                    </button>
                  </div>
                </div>
              </button>
            ))
          )}
        </div>

        {/* Right: demographics form */}
        <div className="c-card p-5">
          {!selected ? (
            <div className="flex flex-col items-center justify-center h-full min-h-[280px]
                            gap-3 text-center">
              <Ic n="voters" s={32} c="#2A2A4A" />
              <div className="text-sm font-semibold text-muted">
                Select a card to fill demographics
              </div>
            </div>
          ) : result ? (
            /* Success state */
            <div className="flex flex-col items-center gap-4 py-4">
              <div className="w-16 h-16 rounded-2xl bg-green-500/10 border border-green-500/20
                              flex items-center justify-center">
                <Ic n="check" s={28} c="#34D399" sw={3} />
              </div>
              <div className="text-center">
                <div className="text-base font-bold text-white mb-1">Voter Registered</div>
                <div className="mono text-xl font-extrabold text-purple-400 mb-2">
                  {result.votingId}
                </div>
                <div className="text-xs text-muted leading-relaxed">
                  {result.pollingUnit}<br/>
                  {result.lga} · {result.state}
                </div>
                <div className="mt-3 text-[11px] text-warning bg-warning/10
                                border border-warning/20 rounded-xl px-3 py-2">
                  {result.message}
                </div>
              </div>
              <button
                className="btn btn-surface btn-md w-full justify-center mt-2"
                onClick={() => { setSelected(null); resetForm(); }}>
                Register Another Voter
              </button>
            </div>
          ) : (
            /* Form */
            <div className="flex flex-col gap-4">
              <div className="text-sm font-bold text-ink">Voter Demographics</div>
              <div className="bg-elevated border border-border rounded-xl p-3 text-xs
                              text-muted mono truncate">
                Card: {selected.cardIdHash}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-bold text-sub uppercase
                                    tracking-wide mb-1.5">
                    First Name <span className="text-danger">*</span>
                  </label>
                  <input className="inp inp-md"
                    placeholder="e.g. Musa"
                    value={firstName} onChange={e => setFirstName(e.target.value)} />
                </div>
                <div>
                  <label className="block text-xs font-bold text-sub uppercase
                                    tracking-wide mb-1.5">
                    Surname <span className="text-danger">*</span>
                  </label>
                  <input className="inp inp-md"
                    placeholder="e.g. Ibrahim"
                    value={surname} onChange={e => setSurname(e.target.value)} />
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold text-sub uppercase
                                  tracking-wide mb-1.5">
                  Date of Birth <span className="text-danger">*</span>
                </label>
                <input className="inp inp-md" type="date"
                  value={dob} onChange={e => setDob(e.target.value)} />
              </div>

              <div>
                <label className="block text-xs font-bold text-sub uppercase
                                  tracking-wide mb-1.5">
                  Gender <span className="text-danger">*</span>
                </label>
                <div className="flex gap-2">
                  {GENDERS.map(g => (
                    <button key={g}
                      className={`flex-1 py-2 rounded-xl border text-xs font-bold
                                  transition-colors
                                  ${gender === g
                                    ? "border-purple-500/50 bg-purple-500/10 text-purple-300"
                                    : "border-border bg-elevated text-sub hover:border-purple-500/20"}`}
                      onClick={() => setGender(g)}>
                      {g}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bg-amber-500/8 border border-amber-500/20 rounded-xl
                              p-3 text-[11px] text-amber-300 leading-relaxed">
                Demographics are encrypted at rest using AES-256.
                Access is permanently audit-logged.
              </div>

              <button
                className="btn btn-primary btn-md w-full justify-center gap-2 mt-1"
                onClick={handleCommit}
                disabled={saving || !firstName.trim() || !surname.trim() || !dob}>
                {saving
                  ? <><Spinner s={16} /> Registering...</>
                  : <><Ic n="check" s={15} c="#fff" /> Register Voter</>}
              </button>
              {commitModal}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
