import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getElections, createElection, deleteElection, unlockCards } from "../api/elections.js";
import { initiateStateChange, signStateChange } from "../api/multisig.js";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { useKeypair } from "../context/KeypairContext.jsx";
import { StatCard, StatusBadge, Pbar, SectionHeader, Modal, Label, Spinner, EmptyState } from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

export default function ElectionsView() {
  const { signChallenge } = useKeypair();
  const navigate = useNavigate();
  const [elections, setElections] = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [showCreate,setShowCreate]= useState(false);
  const [form,      setForm]      = useState({ name:"", type:"PRESIDENTIAL", startTime:"", endTime:"", description:"" });
  const [saving,    setSaving]    = useState(false);
  const [submitting, setSubmitting] = useState(null);
  const [toast,     setToast]     = useState({ msg: "", type: "success" });

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

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
        setForm({ name:"", type:"PRESIDENTIAL", startTime:"", endTime:"", description:"" });
      }
    );

  const load = async () => {
    setLoading(true);
    try {
      const data = await getElections();
      setElections(Array.isArray(data) ? data : []);
    } catch (e) {
      showToast("Failed to load elections: " + (e.response?.data?.error || e.message), "error");
      setElections([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    if (!form.name.trim()) return showToast("Election name is required", "error");
    if (!form.startTime) return showToast("Start date/time is required", "error");
    if (!form.endTime) return showToast("End date/time is required", "error");
    if (new Date(form.endTime) <= new Date(form.startTime)) return showToast("End time must be after start time", "error");

    setSaving(true);
    try {
      createElectionWithAuth();
    } catch (e) {
      showToast("Failed to create: " + (e.response?.data?.error || e.message), "error");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Are you sure you want to delete this draft election? This cannot be undone.")) return;
    setSubmitting(id);
    try {
      await deleteElection(id);
      showToast("Draft election deleted successfully");
      load();
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to delete election", "error");
    } finally {
      setSubmitting(null);
    }
  };

  const handleUnlockCards = async (id) => {
    setSubmitting(id);
    try {
      const res = await unlockCards(id);
      showToast(`${res.cardsUnlocked} smart cards forcefully unlocked.`, "success");
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to broadcast unlock command", "error");
    } finally {
      setSubmitting(null);
    }
  };

  const handleStatus = async (id, status) => {
    if (submitting === id) return;
    setSubmitting(id);
    const action = status === "ACTIVE" ? "ACTIVATE_ELECTION" : "CLOSE_ELECTION";

    try {
      const res = await initiateStateChange(action, id);
      const changeId = res.changeId;

      if (res.status?.executed) {
        showToast(`Election ${status === "ACTIVE" ? "activated" : "closed"} successfully`);
        load();
        return;
      }

      if (signChallenge && changeId) {
        try {
          const sig = await signChallenge(changeId);
          if (sig) {
            const signRes = await signStateChange(changeId, sig);
            if (signRes.executed) {
              showToast(`Election ${status === "ACTIVE" ? "activated" : "closed"} successfully`);
              load();
              return;
            }
          }
        } catch (signErr) {
          if (signErr.response?.status === 409) {
            showToast("Approval submitted — taking you to Approvals...", "warning");
            setTimeout(() => navigate("/approvals"), 800);
            return;
          }
          console.warn("[ElectionsView] Auto-sign failed:", signErr.message);
        }
      }

      showToast("Action initiated! Taking you to Approvals to authorize...", "warning");
      setTimeout(() => navigate("/approvals"), 800);

    } catch (e) {
      showToast(e.response?.data?.error || e.message || "Failed to initiate", "error");
    } finally {
      setSubmitting(null);
    }
  };

  const counts = {
    ACTIVE:  elections.filter(e=>e.status==="ACTIVE").length,
    PENDING: elections.filter(e=>e.status==="PENDING").length,
    CLOSED:  elections.filter(e=>e.status==="CLOSED").length,
  };

  return (
    <div className="p-7 flex flex-col gap-5">
      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                        px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                        ${toast.type === "error" ? "text-danger" : "text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15}/>
          {toast.msg}
          <button onClick={()=>setToast({msg:"", type:"success"})} className="text-muted hover:text-sub ml-1">
            <Ic n="close" s={12}/>
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard label="Active"  value={counts.ACTIVE}  icon="ballot" accent="green"  delay={0}/>
        <StatCard label="Pending" value={counts.PENDING} icon="flag"   accent="amber"  delay={50}/>
        <StatCard label="Closed"  value={counts.CLOSED}  icon="shield" accent="purple" delay={100}/>
      </div>

      <div className="c-card p-6 animate-fade-up" style={{animationDelay:"150ms"}}>
        <SectionHeader
          title="Electoral Lifecycle Management"
          sub={`${elections.length} total elections configured in system`}
          action={
            <button className="btn btn-primary btn-sm flex items-center gap-2" onClick={()=>setShowCreate(true)}>
              <Ic n="plus" s={14} c="#fff" sw={2.5}/> New Election
            </button>
          }
        />

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28}/></div>
        ) : elections.length === 0 ? (
          <EmptyState icon="ballot" title="No elections yet" sub="Create your first election to begin enrolling voters"/>
        ) : (
          <div className="flex flex-col gap-4 mt-2">
            {elections.map((e, i) => {
              const regVoters = e.registeredVoters || 0;
              const cast = e.castVotes || 0;
              const pct = regVoters > 0 ? (cast / regVoters) * 100 : 0;

              return (
                <div key={e.id}
                  className="rounded-2xl border transition-all duration-300 p-5 animate-fade-up"
                  style={{
                    animationDelay: `${i*50+150}ms`,
                    background: e.status==="ACTIVE" ? "rgba(139,92,246,.05)" : "rgba(255,255,255,.01)",
                    borderColor: e.status==="ACTIVE" ? "rgba(139,92,246,.25)" : "rgba(255,255,255,.05)",
                  }}>
                  <div className="flex items-start justify-between flex-wrap gap-4">
                    <div className="flex-1 min-w-[250px]">
                      <div className="flex items-center gap-3 flex-wrap mb-2">
                        <span className="text-[16px] font-bold text-white tracking-wide">{e.name}</span>
                        <StatusBadge status={e.status}/>
                      </div>
                      <div className="mono text-[12px] text-muted flex items-center gap-2">
                        <Ic n="clock" s={12} c="#6B7280" />
                        {e.startTime ? new Date(e.startTime).toLocaleDateString("en-NG",{day:"2-digit",month:"short",year:"numeric"}) : "Not Set"}
                        {" → "}
                        {e.endTime   ? new Date(e.endTime).toLocaleDateString("en-NG",{day:"2-digit",month:"short",year:"numeric"})   : "Not Set"}
                      </div>
                      {e.description && (
                        <div className="text-[12px] text-sub mt-2 max-w-xl leading-relaxed">
                          {e.description}
                        </div>
                      )}
                    </div>

                    {/* ACTION BUTTONS WITH OUR NEW DESIGN */}
                    <div className="flex gap-3 flex-shrink-0">
                      {e.status==="PENDING" && (
                        <>
                          <button className="btn btn-surface border border-green-500/20 hover:border-green-500/50 hover:bg-green-500/10 text-success text-xs px-4 py-2"
                                  onClick={()=>handleStatus(e.id,"ACTIVE")}
                                  disabled={submitting === e.id}>
                            {submitting === e.id ? "..." : <><Ic n="check" s={14} c="#34D399"/> Start Election</>}
                          </button>

                          {/* NEW DELETION DESIGN: Quick delete for drafts */}
                          <button className="btn btn-surface border border-red-500/20 hover:border-red-500/50 hover:bg-red-500/10 text-danger text-xs px-4 py-2"
                                  onClick={()=>handleDelete(e.id)}
                                  disabled={submitting === e.id}>
                            {submitting === e.id ? "..." : <><Ic n="trash" s={14} c="#EF4444"/> Delete</>}
                          </button>
                        </>
                      )}
                      {e.status==="ACTIVE" && (
                        <button className="btn btn-surface border border-red-500/20 hover:border-red-500/50 hover:bg-red-500/10 text-danger text-xs px-4 py-2"
                                onClick={()=>handleStatus(e.id,"CLOSED")}
                                disabled={submitting === e.id}>
                          {submitting === e.id ? "..." : <><Ic n="lock" s={14} c="#EF4444"/> Close Polls</>}
                        </button>
                      )}
                      {e.status==="CLOSED" && (
                        // NEW UNLOCK DESIGN: Manual broadcast override
                        <button className="btn btn-surface border border-purple-500/20 hover:border-purple-500/50 hover:bg-purple-500/10 text-purple-300 text-xs px-4 py-2"
                                onClick={()=>handleUnlockCards(e.id)}
                                disabled={submitting === e.id}>
                          {submitting === e.id ? "Broadcasting..." : <><Ic n="radio" s={14} c="#D8B4FE"/> Broadcast Unlock</>}
                        </button>
                      )}
                    </div>
                  </div>

                  {e.status !== "PENDING" && regVoters > 0 && (
                    <div className="mt-5 pt-4 border-t border-border/50">
                      <div className="flex justify-between items-end mb-2">
                        <span className="text-[12px] font-bold text-sub uppercase tracking-wider">Voter Turnout</span>
                        <span className="mono text-sm font-bold text-purple-300">{pct.toFixed(2)}%</span>
                      </div>
                      <Pbar pct={pct} color={e.status==="ACTIVE"?"#8B5CF6":"#4A4464"} glow={e.status==="ACTIVE"}/>
                      <div className="flex justify-between mt-2">
                        <span className="mono text-[11px] font-bold text-white">
                          {(cast/1e6).toFixed(3)}M <span className="text-muted font-normal">votes cast</span>
                        </span>
                        <span className="mono text-[11px] text-muted">
                          of {(regVoters/1e6).toFixed(1)}M registered
                        </span>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {showCreate && (
        <Modal title="Configure New Election" onClose={()=>setShowCreate(false)}>
          <div className="space-y-5">
            <div>
              <Label>Official Election Title *</Label>
              <input className="inp inp-md bg-elevated border-border-hi text-white w-full"
                placeholder="e.g. 2027 Presidential Election"
                value={form.name} onChange={e=>setForm(p=>({...p,name:e.target.value}))}/>
            </div>

            {/* NEW TYPE DROPDOWN DESIGN */}
            <div>
              <Label>Election Scope / Type *</Label>
              <select
                className="inp inp-md bg-elevated border-border-hi text-white w-full appearance-none"
                value={form.type}
                onChange={e=>setForm(p=>({...p,type:e.target.value}))}
              >
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
                value={form.description} onChange={e=>setForm(p=>({...p,description:e.target.value}))}/>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Label>Scheduled Start</Label>
                <input className="inp inp-md bg-elevated border-border-hi text-white w-full" type="datetime-local"
                  value={form.startTime} onChange={e=>setForm(p=>({...p,startTime:e.target.value}))}/>
              </div>
              <div>
                <Label>Scheduled End</Label>
                <input className="inp inp-md bg-elevated border-border-hi text-white w-full" type="datetime-local"
                  value={form.endTime} onChange={e=>setForm(p=>({...p,endTime:e.target.value}))}/>
              </div>
            </div>
          </div>

          <div className="flex gap-3 mt-8">
            <button className="btn bg-purple-600 hover:bg-purple-500 text-white font-bold py-2.5 flex-1 flex justify-center items-center rounded-xl transition-colors"
              onClick={handleCreate} disabled={saving}>
              {saving ? <Spinner s={16}/> : "Deploy Configuration"}
            </button>
            <button className="btn btn-surface border border-border-hi hover:bg-white/5 py-2.5 flex-1 rounded-xl transition-colors"
              onClick={()=>setShowCreate(false)}>
              Cancel
            </button>
          </div>
        </Modal>
      )}
    {createElectionModal}
    </div>
  );
}