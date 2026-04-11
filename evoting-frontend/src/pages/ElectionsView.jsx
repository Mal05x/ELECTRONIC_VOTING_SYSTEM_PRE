import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import {
  getElections, createElection, deleteElection, unlockCards,
  getCandidates, addCandidate, getParties,
  initiateRemoveCandidate,
} from "../api/elections.js";
import { initiateStateChange, signStateChange } from "../api/multisig.js";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { useKeypair } from "../context/KeypairContext.jsx";
import {
  StatCard, StatusBadge, Pbar, SectionHeader,
  Modal, Label, Spinner, EmptyState,
} from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";


export default function ElectionsView() {
  const { signChallenge } = useKeypair();
  const navigate = useNavigate();

  // Election state
  const [elections,        setElections]        = useState([]);
  const [loading,          setLoading]          = useState(true);
  const [submitting,       setSubmitting]        = useState(null);
  const [toast,            setToast]            = useState({ msg: "", type: "success" });

  // Create election form
  const [showCreate,       setShowCreate]       = useState(false);
  const [saving,           setSaving]           = useState(false);
  const [form,             setForm]             = useState({
    name: "", type: "PRESIDENTIAL", startTime: "", endTime: "", description: "",
  });

  // Candidate panel — expanded election
  const [expandedId,       setExpandedId]       = useState(null);
  const [candidates,       setCandidates]       = useState([]);
  const [candidatesLoading,setCandidatesLoading]= useState(false);
  const [parties,          setParties]          = useState([]);
  const [showAddCandidate, setShowAddCandidate] = useState(false);
  const [candForm,         setCandForm]         = useState({
    fullName: "", partyAbbreviation: "", position: "",
  });
  const [candSaving,       setCandSaving]       = useState(false);
  const [removingId,       setRemovingId]       = useState(null);

  // ── Helpers ──────────────────────────────────────────────────────────────
  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  // ── Load elections ────────────────────────────────────────────────────────
  const load = async () => {
    setLoading(true);
    try {
      const data = await getElections();
      setElections(Array.isArray(data) ? data : []);
    } catch (e) {
      showToast("Failed to load elections: " + (e.response?.data?.error || e.message), "error");
      setElections([]);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  // ── Load candidates when election is expanded ─────────────────────────────
  const loadCandidates = async (electionId) => {
    setCandidatesLoading(true);
    try {
      const [cands, partiesData] = await Promise.all([
        getCandidates(electionId),
        getParties().catch(() => []),
      ]);
      setCandidates(Array.isArray(cands) ? cands : []);
      setParties(Array.isArray(partiesData) ? partiesData : []);
    } catch (e) {
      setCandidates([]);
    } finally { setCandidatesLoading(false); }
  };

  const toggleExpand = (electionId) => {
    if (expandedId === electionId) {
      setExpandedId(null);
      setCandidates([]);
      setShowAddCandidate(false);
    } else {
      setExpandedId(electionId);
      setShowAddCandidate(false);
      loadCandidates(electionId);
    }
  };

  // ── Create election ────────────────────────────────────────────────────────
  const { trigger: createElectionWithAuth, modal: createElectionModal } =
    useStepUpAction(
      "CREATE_ELECTION",
      () => `Create election: "${form.name || "new election"}"`,
      async (headers) => {
        const payload = {
          name:        form.name.trim(),
          type:        form.type,
          description: form.description.trim(),
          startTime:   form.startTime ? new Date(form.startTime).toISOString() : null,
          endTime:     form.endTime   ? new Date(form.endTime).toISOString()   : null,
        };
        await createElection(payload, headers);
        await load();
        showToast("Election created successfully");
        setShowCreate(false);
        setForm({ name: "", type: "PRESIDENTIAL", startTime: "", endTime: "", description: "" });
      }
    );

  const handleCreate = () => {
    if (!form.name.trim())   return showToast("Election name is required", "error");
    if (!form.startTime)     return showToast("Start date/time is required", "error");
    if (!form.endTime)       return showToast("End date/time is required", "error");
    if (new Date(form.endTime) <= new Date(form.startTime))
      return showToast("End time must be after start time", "error");
    createElectionWithAuth();
  };

  // ── Election lifecycle (multisig) ─────────────────────────────────────────
  const handleStatus = async (id, status) => {
    if (submitting === id) return;
    setSubmitting(id);
    const action = status === "ACTIVE" ? "ACTIVATE_ELECTION" : "CLOSE_ELECTION";
    try {
      const res = await initiateStateChange(action, id);
      const changeId = res.changeId;
      if (res.status?.executed) {
        showToast(`Election ${status === "ACTIVE" ? "activated" : "closed"} successfully`);
        load(); return;
      }
      if (signChallenge && changeId) {
        const sig = await signChallenge(res.status?.signingPayload || changeId);
        if (sig) {
          const signRes = await signStateChange(changeId, sig);
          if (signRes.executed) {
            showToast(`Election ${status === "ACTIVE" ? "activated" : "closed"} successfully`);
            load(); return;
          }
        }
      }
      showToast("Action initiated — go to Approvals to co-sign", "warning");
      setTimeout(() => navigate("/approvals"), 800);
    } catch (e) {
      showToast(e.response?.data?.error || e.message || "Failed to initiate", "error");
    } finally { setSubmitting(null); }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this draft election? This cannot be undone.")) return;
    setSubmitting(id);
    try {
      await deleteElection(id);
      showToast("Draft election deleted");
      load();
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to delete", "error");
    } finally { setSubmitting(null); }
  };

  const handleUnlockCards = async (id) => {
    setSubmitting(id);
    try {
      const res = await unlockCards(id);
      showToast(`${res.cardsUnlocked} smart cards unlocked`, "success");
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to unlock", "error");
    } finally { setSubmitting(null); }
  };

  // ── Add candidate ─────────────────────────────────────────────────────────
  const handleAddCandidate = async () => {
    if (!candForm.fullName.trim())            return showToast("Full name is required", "error");
    if (!candForm.partyAbbreviation.trim())   return showToast("Party is required", "error");
    if (!candForm.position.trim())            return showToast("Position is required", "error");
    setCandSaving(true);
    try {
      await addCandidate({
        electionId:          expandedId,
        fullName:            candForm.fullName.trim(),
        partyAbbreviation:   candForm.partyAbbreviation.trim().toUpperCase(),
        position:            candForm.position.trim(),
      });
      showToast("Candidate added");
      setCandForm({ fullName: "", partyAbbreviation: "", position: "" });
      setShowAddCandidate(false);
      loadCandidates(expandedId);
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to add candidate", "error");
    } finally { setCandSaving(false); }
  };


  // ── Remove candidate (multisig) ───────────────────────────────────────────
  const handleRemoveCandidate = async (candidateId, candidateName) => {
    if (!window.confirm(`Initiate removal of "${candidateName}"? This requires multisig approval.`)) return;
    setRemovingId(candidateId);
    try {
      const res = await initiateRemoveCandidate(candidateId);
      if (res.status === "EXECUTED") {
        showToast(`${candidateName} removed`);
        loadCandidates(expandedId);
      } else {
        showToast("Removal initiated — go to Approvals to co-sign", "warning");
        setTimeout(() => navigate("/approvals"), 800);
      }
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to initiate removal", "error");
    } finally { setRemovingId(null); }
  };

  // ── Render ─────────────────────────────────────────────────────────────────
  const counts = {
    ACTIVE:  elections.filter(e => e.status === "ACTIVE").length,
    PENDING: elections.filter(e => e.status === "PENDING").length,
    CLOSED:  elections.filter(e => e.status === "CLOSED").length,
  };

  return (
    <div className="p-7 flex flex-col gap-5">

      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                        px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                        ${toast.type === "error" ? "text-danger" : toast.type === "warning" ? "text-yellow-300" : "text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : toast.type === "warning" ? "warning" : "check"} s={15} />
          {toast.msg}
          <button onClick={() => setToast({ msg: "", type: "success" })} className="text-muted hover:text-sub ml-1">
            <Ic n="close" s={12} />
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard label="Active"  value={counts.ACTIVE}  icon="ballot" accent="green"  delay={0} />
        <StatCard label="Pending" value={counts.PENDING} icon="flag"   accent="amber"  delay={50} />
        <StatCard label="Closed"  value={counts.CLOSED}  icon="shield" accent="purple" delay={100} />
      </div>

      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "150ms" }}>
        <SectionHeader
          title="Electoral Lifecycle Management"
          sub={`${elections.length} total elections · Click an election to manage candidates and photos`}
          action={
            <button className="btn btn-primary btn-sm flex items-center gap-2"
              onClick={() => setShowCreate(true)}>
              <Ic n="plus" s={14} c="#fff" sw={2.5} /> New Election
            </button>
          }
        />

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28} /></div>
        ) : elections.length === 0 ? (
          <EmptyState icon="ballot" title="No elections yet"
            sub="Create your first election to begin enrolling voters" />
        ) : (
          <div className="flex flex-col gap-4 mt-2">
            {elections.map((e, i) => {
              const isExpanded = expandedId === e.id;
              const regVoters = e.registeredVoters || 0;
              const cast = e.castVotes || 0;
              const pct = regVoters > 0 ? (cast / regVoters) * 100 : 0;

              return (
                <div key={e.id}
                  className="rounded-2xl border transition-all duration-300 animate-fade-up overflow-hidden"
                  style={{
                    animationDelay: `${i * 50 + 150}ms`,
                    background: e.status === "ACTIVE" ? "rgba(139,92,246,.05)" : "rgba(255,255,255,.01)",
                    borderColor: isExpanded ? "rgba(139,92,246,.4)" : e.status === "ACTIVE" ? "rgba(139,92,246,.25)" : "rgba(255,255,255,.05)",
                  }}>

                  {/* ── Election header ── */}
                  <div className="p-5">
                    <div className="flex items-start justify-between flex-wrap gap-4">
                      <div className="flex-1 min-w-[250px]">
                        <div className="flex items-center gap-3 flex-wrap mb-2">
                          <button
                            className="text-[16px] font-bold text-white tracking-wide hover:text-purple-300 transition-colors flex items-center gap-2"
                            onClick={() => toggleExpand(e.id)}>
                            {e.name}
                            <Ic n={isExpanded ? "chevron" : "chevron"} s={14} c="#6B7280"
                              className={`transition-transform ${isExpanded ? "rotate-90" : ""}`} />
                          </button>
                          <StatusBadge status={e.status} />
                        </div>
                        <div className="mono text-[12px] text-muted flex items-center gap-2">
                          <Ic n="clock" s={12} c="#6B7280" />
                          {e.startTime ? new Date(e.startTime).toLocaleDateString("en-NG", { day: "2-digit", month: "short", year: "numeric" }) : "Not Set"}
                          {" → "}
                          {e.endTime   ? new Date(e.endTime).toLocaleDateString("en-NG", { day: "2-digit", month: "short", year: "numeric" }) : "Not Set"}
                        </div>
                        {e.description && (
                          <div className="text-[12px] text-sub mt-2 max-w-xl leading-relaxed">
                            {e.description}
                          </div>
                        )}
                      </div>

                      {/* Action buttons */}
                      <div className="flex gap-2 flex-shrink-0 flex-wrap">
                        {e.status === "PENDING" && (
                          <>
                            <button
                              className="btn btn-surface border border-green-500/20 hover:border-green-500/50 hover:bg-green-500/10 text-success text-xs px-4 py-2"
                              onClick={() => handleStatus(e.id, "ACTIVE")}
                              disabled={submitting === e.id}>
                              {submitting === e.id ? "..." : <><Ic n="check" s={14} c="#34D399" /> Start Election</>}
                            </button>
                            <button
                              className="btn btn-surface border border-red-500/20 hover:border-red-500/50 hover:bg-red-500/10 text-danger text-xs px-4 py-2"
                              onClick={() => handleDelete(e.id)}
                              disabled={submitting === e.id}>
                              <Ic n="trash" s={14} c="#EF4444" /> Delete
                            </button>
                          </>
                        )}
                        {e.status === "ACTIVE" && (
                          <button
                            className="btn btn-surface border border-red-500/20 hover:border-red-500/50 hover:bg-red-500/10 text-danger text-xs px-4 py-2"
                            onClick={() => handleStatus(e.id, "CLOSED")}
                            disabled={submitting === e.id}>
                            {submitting === e.id ? "..." : <><Ic n="lock" s={14} c="#EF4444" /> Close Polls</>}
                          </button>
                        )}
                        {e.status === "CLOSED" && (
                          <button
                            className="btn btn-surface border border-purple-500/20 hover:border-purple-500/50 hover:bg-purple-500/10 text-purple-300 text-xs px-4 py-2"
                            onClick={() => handleUnlockCards(e.id)}
                            disabled={submitting === e.id}>
                            {submitting === e.id ? "Broadcasting..." : <><Ic n="radio" s={14} c="#D8B4FE" /> Broadcast Unlock</>}
                          </button>
                        )}
                        <button
                          className={`btn btn-surface text-xs px-3 py-2 border transition-all
                            ${isExpanded ? "border-purple-500/40 text-purple-300 bg-purple-500/8" : "border-border text-sub hover:border-purple-500/20"}`}
                          onClick={() => toggleExpand(e.id)}
                          title="Manage candidates">
                          <Ic n="voters" s={13} className="mr-1" />
                          Candidates
                        </button>
                      </div>
                    </div>

                    {e.status !== "PENDING" && regVoters > 0 && (
                      <div className="mt-4 pt-4 border-t border-border/50">
                        <div className="flex justify-between items-end mb-2">
                          <span className="text-[12px] font-bold text-sub uppercase tracking-wider">Voter Turnout</span>
                          <span className="mono text-sm font-bold text-purple-300">{pct.toFixed(2)}%</span>
                        </div>
                        <Pbar pct={pct} color={e.status === "ACTIVE" ? "#8B5CF6" : "#4A4464"} glow={e.status === "ACTIVE"} />
                      </div>
                    )}
                  </div>

                  {/* ── Candidate panel (expandable) ── */}
                  {isExpanded && (
                    <div className="border-t border-border/50 bg-black/20">
                      <div className="p-5">

                        {/* Panel header */}
                        <div className="flex items-center justify-between mb-4">
                          <div className="text-xs font-bold text-purple-400 uppercase tracking-widest">
                            Candidates — {e.name}
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              className="btn btn-surface btn-sm text-[11px] gap-1.5"
                              onClick={() => loadCandidates(e.id)}>
                              <Ic n="refresh" s={11} /> Refresh
                            </button>
                            <button
                              className="btn btn-purple btn-sm text-[11px] gap-1.5"
                              onClick={() => setShowAddCandidate(v => !v)}>
                              <Ic n="plus" s={11} /> Add Candidate
                            </button>
                          </div>
                        </div>



                                                {/* Photo hint — upload photos in Import → Photos tab */}
                        <div className="flex items-center gap-2 bg-purple-500/8 border border-purple-500/15 rounded-xl px-3 py-2 mb-3">
                          <Ic n="shield" s={12} c="#A78BFA" />
                          <p className="text-[11px] text-sub">
                            To upload candidate photos go to the <span className="font-bold text-purple-300">Import &rarr; Photos</span> tab.
                          </p>
                        </div>

                        {/* Add candidate inline form */}
                        {showAddCandidate && (
                          <div className="bg-card border border-border rounded-xl p-4 mb-4 animate-fade-up">
                            <div className="text-xs font-bold text-ink mb-3">New Candidate</div>
                            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
                              <div>
                                <Label>Full Name *</Label>
                                <input className="inp inp-md"
                                  placeholder="e.g. Abubakar Musa"
                                  value={candForm.fullName}
                                  onChange={ev => setCandForm(p => ({ ...p, fullName: ev.target.value }))} />
                              </div>
                              <div>
                                <Label>Party Abbreviation *</Label>
                                <input className="inp inp-md mono uppercase"
                                  placeholder="e.g. APC"
                                  value={candForm.partyAbbreviation}
                                  onChange={ev => setCandForm(p => ({ ...p, partyAbbreviation: ev.target.value.toUpperCase() }))} />
                                {parties.length > 0 && (
                                  <div className="flex flex-wrap gap-1 mt-1.5">
                                    {parties.map(p => (
                                      <button key={p.id}
                                        className="text-[10px] px-1.5 py-0.5 rounded bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors font-mono"
                                        onClick={() => setCandForm(prev => ({ ...prev, partyAbbreviation: p.abbreviation }))}>
                                        {p.abbreviation}
                                      </button>
                                    ))}
                                  </div>
                                )}
                              </div>
                              <div>
                                <Label>Position / Office *</Label>
                                <input className="inp inp-md"
                                  placeholder="e.g. President"
                                  value={candForm.position}
                                  onChange={ev => setCandForm(p => ({ ...p, position: ev.target.value }))} />
                              </div>
                            </div>
                            <div className="flex gap-2">
                              <button className="btn btn-purple btn-sm" onClick={handleAddCandidate} disabled={candSaving}>
                                {candSaving ? <Spinner s={12} /> : <Ic n="check" s={12} />}
                                {candSaving ? "Adding…" : "Add"}
                              </button>
                              <button className="btn btn-surface btn-sm" onClick={() => setShowAddCandidate(false)}>
                                Cancel
                              </button>
                            </div>
                          </div>
                        )}

                        {/* Candidate list */}
                        {candidatesLoading ? (
                          <div className="flex justify-center py-8"><Spinner s={22} /></div>
                        ) : candidates.length === 0 ? (
                          <div className="flex flex-col items-center py-8 gap-2 text-center">
                            <Ic n="voters" s={28} c="#2A2A4A" />
                            <p className="text-xs text-muted">No candidates yet.</p>
                            <p className="text-[11px] text-muted">
                              Add candidates above, or use the <span className="text-purple-400 font-semibold">Import</span> tab for bulk CSV import.
                            </p>
                          </div>
                        ) : (
                          <div className="flex flex-col gap-2">
                            {/* Column headers */}
                            <div className="hidden md:grid grid-cols-[44px_1fr_80px_120px_100px_80px] gap-3 px-3 py-1">
                              {["Name", "Party", "Position", "Status", "Actions"].map(h => (
                                <span key={h} className="sect-lbl text-[10px]">{h}</span>
                              ))}
                            </div>

                            {candidates.map((c, ci) => (
                              <div key={c.id || ci}
                                className="grid grid-cols-[44px_1fr_80px_120px_100px_80px] gap-3 items-center
                                           px-3 py-2.5 rounded-xl hover:bg-white/3 transition-colors
                                           border border-transparent hover:border-border/30">



                                {/* Name */}
                                <span className="text-sm font-semibold text-ink truncate">
                                  {c.fullName || c.name || "—"}
                                </span>

                                {/* Party */}
                                <span className="badge badge-purple text-[10px] self-start mt-1">
                                  {c.party || c.partyAbbreviation || "—"}
                                </span>

                                {/* Position */}
                                <span className="text-[11px] text-sub truncate">
                                  {c.position || "—"}
                                </span>

                                {/* Photo status */}
                                <div>
                                  {c.imageUrl ? (
                                    <span className="text-[10px] font-semibold text-success flex items-center gap-1">✓ Photo</span>
                                  ) : (
                                    <span className="text-[10px] text-muted">No photo</span>
                                  )}
                                </div>

                                {/* Remove */}
                                <button
                                  className="btn btn-surface btn-sm !text-[10px] text-danger border-red-500/20
                                             hover:bg-red-500/10 hover:border-red-500/40"
                                  onClick={() => handleRemoveCandidate(c.id, c.fullName)}
                                  disabled={removingId === c.id}>
                                  {removingId === c.id ? <Spinner s={10} /> : "Remove"}
                                </button>
                              </div>
                            ))}

                            <p className="text-[10px] text-muted text-center pt-2">
                              {candidates.length} candidate{candidates.length !== 1 ? "s" : ""} ·{" "}
                              {candidates.filter(c => c.imageUrl).length} with photos · {candidates.filter(c => !c.imageUrl).length} without
                            </p>
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Create election modal */}
      {showCreate && (
        <Modal title="Configure New Election" onClose={() => setShowCreate(false)}>
          <div className="space-y-5">
            <div>
              <Label>Official Election Title *</Label>
              <input className="inp inp-md bg-elevated border-border-hi text-white w-full"
                placeholder="e.g. 2027 Presidential Election"
                value={form.name} onChange={ev => setForm(p => ({ ...p, name: ev.target.value }))} />
            </div>
            <div>
              <Label>Election Scope / Type *</Label>
              <select className="inp inp-md bg-elevated border-border-hi text-white w-full appearance-none"
                value={form.type} onChange={ev => setForm(p => ({ ...p, type: ev.target.value }))}>
                <option value="PRESIDENTIAL">Presidential (National)</option>
                <option value="GUBERNATORIAL">Gubernatorial (State)</option>
                <option value="SENATORIAL">Senatorial (Regional)</option>
                <option value="STATE_ASSEMBLY">State Assembly</option>
                <option value="LOCAL_GOVERNMENT">Local Government (LGA)</option>
              </select>
            </div>
            <div>
              <Label>Description / Mandate</Label>
              <input className="inp inp-md bg-elevated border-border-hi text-white w-full"
                placeholder="Optional details regarding this cycle"
                value={form.description} onChange={ev => setForm(p => ({ ...p, description: ev.target.value }))} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Label>Scheduled Start</Label>
                <input className="inp inp-md bg-elevated border-border-hi text-white w-full" type="datetime-local"
                  value={form.startTime} onChange={ev => setForm(p => ({ ...p, startTime: ev.target.value }))} />
              </div>
              <div>
                <Label>Scheduled End</Label>
                <input className="inp inp-md bg-elevated border-border-hi text-white w-full" type="datetime-local"
                  value={form.endTime} onChange={ev => setForm(p => ({ ...p, endTime: ev.target.value }))} />
              </div>
            </div>
          </div>
          <div className="flex gap-3 mt-8">
            <button
              className="btn bg-purple-600 hover:bg-purple-500 text-white font-bold py-2.5 flex-1 flex justify-center items-center rounded-xl transition-colors"
              onClick={handleCreate} disabled={saving}>
              {saving ? <Spinner s={16} /> : "Deploy Configuration"}
            </button>
            <button
              className="btn btn-surface border border-border-hi hover:bg-white/5 py-2.5 flex-1 rounded-xl transition-colors"
              onClick={() => setShowCreate(false)}>
              Cancel
            </button>
          </div>
        </Modal>
      )}
      {createElectionModal}
    </div>
  );
}
