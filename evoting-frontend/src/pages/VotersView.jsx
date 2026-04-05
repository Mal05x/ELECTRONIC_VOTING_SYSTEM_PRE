import { useState, useEffect, useCallback } from "react";
import { getVoters, setCardLock, bulkUnlockCards } from "../api/voters.js";
import { getElections } from "../api/elections.js";
import { useKeypair } from "../context/KeypairContext.jsx";
import { getPendingRegistrations, commitRegistration, cancelPendingRegistration } from "../api/registration.js";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { initiateStateChange, signStateChange } from "../api/multisig.js";
// webcrypto helpers accessed via useKeypair() context
import { StatCard, StatusBadge, SectionHeader, EmptyState, Spinner, Ic } from "../components/ui.jsx";

const FILTERS = ["ALL", "VOTED", "PENDING", "LOCKED", "NOT ENROLLED"];
const TABS    = ["REGISTERED", "PENDING CARDS"];

function Toast({ msg, type, onClose }) {
  if (!msg) return null;
  const s = { error: "border-red-500/30 text-danger", warning: "border-yellow-500/30 text-yellow-300", success: "border-purple-500/30 text-purple-300" };
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5 rounded-2xl border bg-card shadow-card text-sm font-semibold animate-slide-in ${s[type] || s.success}`}>
      <Ic n={type === "error" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="ml-1 text-muted hover:text-sub"><Ic n="close" s={12} /></button>
    </div>
  );
}

export default function VotersView() {
  const { signChallenge } = useKeypair() || {};
  const [tab,          setTab]          = useState("REGISTERED");
  const [elections,    setElections]    = useState([]);
  const [electionId,   setElectionId]   = useState(null);
  const [voters,       setVoters]       = useState([]);
  const [pending,      setPending]      = useState([]);
  const [loading,      setLoading]      = useState(true);
  const [elecLoading,  setElecLoading]  = useState(true);
  const [pendingLoad,  setPendingLoad]  = useState(false);
  const [query,        setQuery]        = useState("");
  const [filter,       setFilter]       = useState("ALL");
  const [locking,      setLocking]      = useState(null);
  const [toast,        setToast]        = useState({ msg: "", type: "success" });
  const [bulkModal,    setBulkModal]    = useState(false);
  const [bulkUnlocking,setBulkUnlocking]= useState(false);

  // Commit demographics modal
  const [commitModal,  setCommitModal]  = useState(false);
  const [commitHeaders, setCommitHeaders] = useState(null);

  const { trigger: commitWithAuth, modal: commitAuthModal } =
    useStepUpAction(
      "COMMIT_REGISTRATION",
      () => commitTarget
        ? `Complete registration for card ${commitTarget.cardIdHash?.slice(0,12)}…`
        : "Complete voter registration",
      async (headers) => {
        // Step-up authorized — store headers and immediately proceed with commit
        setCommitHeaders(headers);
        await handleCommitWithHeaders(headers);
      }
    );
  const [commitTarget, setCommitTarget] = useState(null);
  const [commitSaving, setCommitSaving] = useState(false);
  const [commitResult, setCommitResult] = useState(null);
  const [form, setForm] = useState({ firstName: "", surname: "", dateOfBirth: "", gender: "MALE" });

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4000);
  };

  useEffect(() => {
    setElecLoading(true);
    getElections()
      .then(data => {
        setElections(data);
        const active = data.find(e => e.status === "ACTIVE") || data[0];
        if (active) setElectionId(active.id);
      })
      .catch(() => {})
      .finally(() => setElecLoading(false));
  }, []);

  const loadVoters = useCallback(() => {
    if (!electionId) return;
    setLoading(true);
    getVoters(electionId)
      .then(data => setVoters(Array.isArray(data) ? data : []))
      .catch(() => setVoters([]))
      .finally(() => setLoading(false));
  }, [electionId]);

  const loadPending = useCallback(() => {
    setPendingLoad(true);
    getPendingRegistrations()
      .then(data => setPending(data.pending || []))
      .catch(() => setPending([]))
      .finally(() => setPendingLoad(false));
  }, []);

  useEffect(() => { loadVoters(); }, [loadVoters]);
  useEffect(() => { if (tab === "PENDING CARDS") loadPending(); }, [tab, loadPending]);

  const filtered = voters.filter(v => {
    const matchFilter =
      filter === "ALL"          ? true :
      filter === "VOTED"        ? v.hasVoted :
      filter === "PENDING"      ? !v.hasVoted :
      filter === "LOCKED"       ? v.cardLocked :
      filter === "NOT ENROLLED" ? !v.enrolled : true;
    if (!matchFilter) return false;
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return (v.votingId || "").toLowerCase().includes(q) ||
           (v.pollingUnit?.name || "").toLowerCase().includes(q) ||
           (v.firstName || "").toLowerCase().includes(q) ||
           (v.surname   || "").toLowerCase().includes(q);
  });

  const counts = {
    total:       voters.length,
    voted:       voters.filter(v => v.hasVoted).length,
    pending:     voters.filter(v => !v.hasVoted).length,
    locked:      voters.filter(v => v.cardLocked).length,
    notEnrolled: voters.filter(v => !v.enrolled).length,
  };

  const toggleLock = async (v) => {
    setLocking(v.id);
    try {
      await setCardLock(v.cardIdHash, electionId, !v.cardLocked);
      setVoters(p => p.map(x => x.id === v.id ? { ...x, cardLocked: !v.cardLocked } : x));
      showToast(`Card ${!v.cardLocked ? "locked" : "unlocked"}`);
    } catch (e) {
      showToast("Error: " + (e.response?.data?.error || e.message), "error");
    } finally { setLocking(null); }
  };

  const handleBulkUnlock = async () => {
    if (!electionId) return;
    setBulkUnlocking(true);
    try {
      // Bulk unlock requires multi-sig — initiate state change
      const res = await initiateStateChange("BULK_UNLOCK_CARDS", electionId);
      if (res.status?.executed) {
        showToast("Cards bulk unlocked (single-admin mode)");
        loadVoters();
      } else if (signChallenge && res.changeId) {
        try {
          const sig = await signChallenge(res.changeId);
          if (sig) {
            const signed = await signStateChange(res.changeId, sig);
            if (signed.executed) { showToast("Cards bulk unlocked"); loadVoters(); return; }
          }
        } catch (signErr) {
          console.warn("[VotersView] Bulk unlock auto-sign failed:", signErr.message);
        }
        showToast("Bulk unlock submitted — awaiting co-signature in Pending Approvals", "warning");
      } else {
        showToast("No signing key registered. Set up your key in Settings → Security.", "error");
      }
      setBulkModal(false);
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to initiate bulk unlock", "error");
    } finally { setBulkUnlocking(false); }
  };

  const openCommit = (rec) => {
    setCommitTarget(rec);
    setCommitHeaders(null);
    setForm({ firstName: "", surname: "", dateOfBirth: "", gender: "MALE" });
    setCommitResult(null);
    setCommitModal(true);
  };

  const handleCommitWithHeaders = async (headers) => {
    if (!form.firstName.trim() || !form.surname.trim() || !form.dateOfBirth) {
      showToast("First name, surname, and date of birth are required", "error"); return;
    }
    setCommitSaving(true);
    try {
      const result = await commitRegistration(commitTarget.pendingId, form, headers || commitHeaders || {});
      setCommitResult(result);
      loadVoters();
      loadPending();
    } catch (e) {
      showToast(e.response?.data?.error || "Registration failed", "error");
    } finally { setCommitSaving(false); }
  };

  const handleCancelPending = async (id) => {
    try {
      await cancelPendingRegistration(id);
      showToast("Pending registration cancelled");
      loadPending();
    } catch (e) {
      showToast("Cancel failed: " + (e.response?.data?.error || e.message), "error");
    }
  };

  return (
    <div className="p-7 flex flex-col gap-5">
      <Toast msg={toast.msg} type={toast.type} onClose={() => setToast({ msg: "", type: "success" })} />

      {/* Stats */}
      <div className="grid grid-cols-2 xl:grid-cols-5 gap-4">
        <StatCard label="Total Voters"   value={counts.total}       icon="voters"  accent="purple" delay={0}   />
        <StatCard label="Voted"          value={counts.voted}        icon="vote"    accent="green"  delay={50}  />
        <StatCard label="Not Yet Voted"  value={counts.pending}      icon="flag"    accent="amber"  delay={100} />
        <StatCard label="Locked Cards"   value={counts.locked}       icon="lock"    accent="red"    delay={150} />
        <StatCard label="Not Enrolled"   value={counts.notEnrolled}  icon="chip"    accent="blue"   delay={200} />
      </div>

      {/* Election selector */}
      {elections.length > 1 && (
        <div className="c-card px-5 py-3 flex items-center gap-4">
          <label className="text-xs font-bold text-sub uppercase tracking-wider whitespace-nowrap">Election</label>
          <select className="inp inp-md flex-1 max-w-sm"
            value={electionId || ""} onChange={e => setElectionId(e.target.value)}>
            {elections.map(e => <option key={e.id} value={e.id}>{e.name} — {e.status}</option>)}
          </select>
        </div>
      )}

      {/* Tab bar */}
      <div className="flex items-center justify-between">
        <div className="flex gap-2">
          {TABS.map(t => (
            <button key={t}
              className={`btn btn-sm gap-2 ${tab === t ? "btn-primary" : "btn-ghost"}`}
              onClick={() => setTab(t)}>
              {t === "PENDING CARDS" && pending.length > 0 && (
                <span className="w-4 h-4 rounded-full bg-orange-500 text-white text-[9px] font-bold flex items-center justify-center">
                  {pending.length}
                </span>
              )}
              {t}
            </button>
          ))}
        </div>
      </div>

      {/* ── REGISTERED VOTERS TAB ── */}
      {tab === "REGISTERED" && (
        <div className="c-card p-6 animate-fade-up">
          <div className="flex flex-col sm:flex-row gap-3 mb-5">
            <div className="relative flex-1">
              <div className="absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none">
                <Ic n="search" s={15} c="#4A4464" />
              </div>
              <input className="inp inp-md pl-10" placeholder="Search name, Voting ID, Polling Unit…"
                value={query} onChange={e => setQuery(e.target.value)} />
            </div>
            <div className="flex gap-1.5 flex-wrap">
              {FILTERS.map(f => (
                <button key={f} className={`btn btn-sm ${filter === f ? "btn-primary" : "btn-ghost"}`}
                  onClick={() => setFilter(f)}>
                  {f}
                </button>
              ))}
            </div>
          </div>

          <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
            style={{ gridTemplateColumns: "120px 120px 160px 1fr 130px 70px 70px 90px" }}>
            {["Surname", "First Name", "Voting ID", "Polling Unit", "LGA", "Voted", "Card", "Action"].map(h => (
              <span key={h} className="sect-lbl">{h}</span>
            ))}
          </div>
          <hr className="divider mb-1" />

          {elecLoading || loading ? (
            <div className="flex justify-center py-16"><Spinner s={28} /></div>
          ) : filtered.length === 0 ? (
            <EmptyState icon="voters" title="No voters found" sub="Adjust your search or filter" />
          ) : (
            filtered.map((v, i) => (
              <div key={v.id} className="trow animate-fade-up"
                style={{ gridTemplateColumns: "120px 120px 160px 1fr 130px 70px 70px 90px", gap: "12px", animationDelay: `${i * 15}ms` }}>
                <span className="text-xs font-semibold text-ink truncate">{v.surname || "—"}</span>
                <span className="text-xs font-medium text-sub truncate">{v.firstName || "—"}</span>
                <span className="mono text-[11px] text-purple-400 truncate">{v.votingId}</span>
                <span className="text-xs text-sub truncate hidden xl:block">{v.pollingUnit?.name || "—"}</span>
                <span className="text-xs text-sub truncate hidden xl:block">{v.pollingUnit?.lga?.name || "—"}</span>
                <span className="hidden xl:block"><StatusBadge status={v.hasVoted ? "voted" : "open"} /></span>
                <span className="hidden xl:block"><StatusBadge status={v.cardLocked ? "locked" : "open"} /></span>
                <button
                  className={`btn btn-sm ${v.cardLocked ? "btn-success" : "btn-danger"} !gap-1.5`}
                  onClick={() => toggleLock(v)} disabled={locking === v.id}>
                  {locking === v.id
                    ? <Spinner s={12} />
                    : <Ic n={v.cardLocked ? "unlock" : "lock"} s={12} c={v.cardLocked ? "#34D399" : "#F87171"} />}
                  {v.cardLocked ? "Unlock" : "Lock"}
                </button>
              </div>
            ))
          )}

          <div className="mt-4 pt-3 border-t border-border flex items-center justify-between">
            <span className="text-xs text-muted">{filtered.length} voters shown</span>
            <div className="flex gap-2">
              <button className="btn btn-surface btn-sm gap-1.5" onClick={loadVoters}>
                <Ic n="refresh" s={13} /> Refresh
              </button>
              {counts.locked > 0 && (
                <button className="btn btn-sm gap-1.5 border border-orange-500/30
                                   bg-orange-500/10 text-orange-300 hover:bg-orange-500/20
                                   transition-colors rounded-xl text-xs font-semibold px-3 py-1.5"
                  onClick={() => setBulkModal(true)}>
                  <Ic n="unlock" s={13} c="#fb923c" />
                  Bulk Unlock ({counts.locked})
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── PENDING CARDS TAB ── */}
      {tab === "PENDING CARDS" && (
        <div className="c-card p-6 animate-fade-up">
          <SectionHeader title="Cards Awaiting Demographics"
            sub="Cards scanned by terminals — fill in voter details to complete registration" />

          {pendingLoad ? (
            <div className="flex justify-center py-16"><Spinner s={28} /></div>
          ) : pending.length === 0 ? (
            <EmptyState icon="chip" title="No pending cards"
              sub="When a terminal scans a JCOP4 card for registration, it will appear here" />
          ) : (
            <div className="flex flex-col gap-3 mt-4">
              {pending.map(rec => (
                <div key={rec.pendingId}
                  className="bg-elevated border border-border rounded-xl p-4 flex items-center gap-4">
                  <div className="w-9 h-9 rounded-lg bg-purple-500/10 border border-purple-500/20
                                  flex items-center justify-center flex-shrink-0">
                    <Ic n="chip" s={16} c="#A78BFA" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="mono text-xs font-bold text-purple-400 truncate">{rec.cardIdHash}</div>
                    <div className="text-xs text-muted mt-0.5">
                      Terminal: {rec.terminalId} · Scanned: {new Date(rec.initiatedAt).toLocaleString()}
                    </div>
                    <div className="text-[10px] text-muted">
                      Expires: {new Date(rec.expiresAt).toLocaleString()}
                    </div>
                  </div>
                  <div className="flex gap-2 flex-shrink-0">
                    <button className="btn btn-primary btn-sm gap-1.5"
                      onClick={() => openCommit(rec)}>
                      <Ic n="voters" s={13} c="#fff" /> Add Details
                    </button>
                    <button className="btn btn-danger btn-sm"
                      onClick={() => handleCancelPending(rec.pendingId)}
                      title="Cancel this pending registration">
                      <Ic n="close" s={13} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          <div className="mt-4 pt-3 border-t border-border flex justify-end">
            <button className="btn btn-surface btn-sm gap-1.5" onClick={loadPending}>
              <Ic n="refresh" s={13} /> Refresh
            </button>
          </div>
        </div>
      )}

      {/* ── Commit Demographics Modal ── */}
      {commitModal && commitTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm"
          onClick={e => e.target === e.currentTarget && !commitSaving && setCommitModal(false)}>
          <div className="w-full max-w-md bg-card border border-border-hi rounded-2xl shadow-2xl animate-fade-up">

            <div className="flex items-center justify-between p-6 border-b border-border">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-purple-500/15 border border-purple-500/25
                                flex items-center justify-center flex-shrink-0">
                  <Ic n="voters" s={20} c="#A78BFA" />
                </div>
                <div>
                  <div className="text-base font-bold text-white">Complete Registration</div>
                  <div className="mono text-[10px] text-muted mt-0.5">{commitTarget.cardIdHash}</div>
                </div>
              </div>
              <button className="text-muted hover:text-white" onClick={() => setCommitModal(false)} disabled={commitSaving}>
                <Ic n="close" s={16} />
              </button>
            </div>

            {commitResult ? (
              <div className="p-6 flex flex-col items-center gap-4">
                <div className="w-14 h-14 rounded-2xl bg-green-500/10 border border-green-500/20
                                flex items-center justify-center">
                  <Ic n="check" s={28} c="#34D399" sw={3} />
                </div>
                <div className="text-center">
                  <div className="text-base font-bold text-white mb-1">Voter Registered</div>
                  <div className="mono text-lg font-extrabold text-purple-400">{commitResult.votingId}</div>
                  <div className="text-xs text-muted mt-1">{commitResult.pollingUnit} · {commitResult.lga} · {commitResult.state}</div>
                  <div className="text-[11px] text-orange-300 mt-2 bg-orange-500/10 border border-orange-500/20 rounded-lg px-3 py-2">
                    Present card at terminal for biometric enrollment
                  </div>
                </div>
                <button className="btn btn-primary btn-md w-full justify-center" onClick={() => setCommitModal(false)}>
                  Done
                </button>
              </div>
            ) : (
              <div className="p-6 flex flex-col gap-4">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-bold text-sub uppercase tracking-wide mb-1.5">
                      Surname <span className="text-danger">*</span>
                    </label>
                    <input className="inp inp-md" placeholder="Ibrahim"
                      value={form.surname} onChange={e => setForm(f => ({ ...f, surname: e.target.value }))} />
                  </div>
                  <div>
                    <label className="block text-xs font-bold text-sub uppercase tracking-wide mb-1.5">
                      First Name <span className="text-danger">*</span>
                    </label>
                    <input className="inp inp-md" placeholder="Musa"
                      value={form.firstName} onChange={e => setForm(f => ({ ...f, firstName: e.target.value }))} />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-bold text-sub uppercase tracking-wide mb-1.5">
                    Date of Birth <span className="text-danger">*</span>
                  </label>
                  <input className="inp inp-md" type="date"
                    value={form.dateOfBirth} onChange={e => setForm(f => ({ ...f, dateOfBirth: e.target.value }))} />
                </div>
                <div>
                  <label className="block text-xs font-bold text-sub uppercase tracking-wide mb-1.5">Gender</label>
                  <select className="inp inp-md"
                    value={form.gender} onChange={e => setForm(f => ({ ...f, gender: e.target.value }))}>
                    <option value="MALE">Male</option>
                    <option value="FEMALE">Female</option>
                    <option value="OTHER">Other</option>
                  </select>
                </div>
                <div className="bg-purple-500/8 border border-purple-500/20 rounded-xl p-3">
                  <div className="text-[10px] text-purple-300 leading-relaxed">
                    <Ic n="shield" s={11} c="#A78BFA" /> Demographics are encrypted at rest with AES-256.
                    Every decryption is permanently recorded in the audit log.
                  </div>
                </div>
                <div className="flex gap-3 pt-1">
                  <button className="btn btn-md flex-1 justify-center border border-white/8
                                     bg-white/3 text-sub hover:bg-white/6 rounded-xl text-sm font-semibold"
                    onClick={() => setCommitModal(false)} disabled={commitSaving}>
                    Cancel
                  </button>
                  <button className="btn btn-primary btn-md flex-1 justify-center gap-2"
                    onClick={commitWithAuth}
                    disabled={commitSaving || !form.firstName.trim() || !form.surname.trim() || !form.dateOfBirth}>
                    {commitSaving ? <><Spinner s={15} /> Registering...</> : <><Ic n="check" s={15} c="#fff" /> Register Voter</>}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── Bulk Unlock Modal (now routes through multi-sig) ── */}
      {bulkModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-6 bg-black/70 backdrop-blur-sm"
          onClick={e => e.target === e.currentTarget && setBulkModal(false)}>
          <div className="w-full max-w-md bg-card border border-border-hi rounded-2xl p-8 shadow-2xl animate-fade-up">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-orange-500/15 border border-orange-500/25
                              flex items-center justify-center flex-shrink-0">
                <Ic n="unlock" s={20} c="#fb923c" />
              </div>
              <div>
                <div className="text-base font-bold text-white">Bulk Unlock Cards</div>
                <div className="text-xs text-sub mt-0.5">Requires multi-signature approval</div>
              </div>
            </div>
            <p className="text-sm text-sub mb-4 leading-relaxed">
              This will initiate a multi-signature approval request to unlock all {counts.locked} locked cards.
              A second SUPER_ADMIN must approve it in <strong className="text-ink">Pending Approvals</strong>.
            </p>
            <div className="flex gap-3">
              <button className="btn btn-md flex-1 justify-center border border-white/8
                                 bg-white/3 text-sub hover:bg-white/6 rounded-xl text-sm font-semibold"
                onClick={() => setBulkModal(false)}>Cancel</button>
              <button className="btn btn-md flex-1 justify-center gap-2
                                 bg-orange-500/20 border border-orange-500/40 text-orange-300
                                 hover:bg-orange-500/30 rounded-xl text-sm font-semibold"
                onClick={handleBulkUnlock} disabled={bulkUnlocking}>
                {bulkUnlocking ? <Spinner s={15} /> : <Ic n="unlock" s={15} c="#fb923c" />}
                Initiate Unlock
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
