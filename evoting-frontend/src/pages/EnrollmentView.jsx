import { useState, useEffect } from "react";
import client from "../api/client.js";
// Add this:
import { getEnrollmentQueue, queueEnrollment, cancelEnrollment } from "../api/enrollment.js";
import { getStates, getLgasByState, getPollingUnitsByLga } from "../api/locations.js"; // NEW IMPORTS
import { StatCard, StatusBadge, SectionHeader, Modal, Label, Spinner, EmptyState } from "../components/ui.jsx";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { Ic } from "../components/ui.jsx";

export default function EnrollmentView() {
  const [queue,     setQueue]     = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [queueHeaders, setQueueHeaders] = useState(null);

  const { trigger: queueWithAuth, modal: queueAuthModal } =
    useStepUpAction(
      "QUEUE_ENROLLMENT",
      () => `Queue enrollment for terminal ${form.terminalId || "?"}`,
      async (headers) => { setQueueHeaders(headers); }
    );
  const [saving,    setSaving]    = useState(false);
  const [retryingId, setRetryingId] = useState(null);
  const [toast,     setToast]     = useState({ msg:"", type:"success" });

  // NEW: State for the cascading dropdowns
  const [locStates, setLocStates] = useState([]);
  const [lgas,      setLgas]      = useState([]);
  const [pus,       setPus]       = useState([]);
  const [puFetched, setPuFetched] = useState(false); // true once PU fetch completes
  const [puError,   setPuError]   = useState(null);  // error message if PU fetch fails

  const [form, setForm] = useState({
    terminalId:"", stateId:"", lgaId:"", pollingUnit:""
  });

  const showToast = (msg, type="success") => {
    setToast({msg,type});
    setTimeout(()=>setToast({msg:"",type:"success"}),3500);
  };

  const load = async () => {
    setLoading(true);
    try {
      // Enrollment is permanent — no election filter needed (V8 schema)
      // Calls your wrapper (with no electionId)
            const data = await getEnrollmentQueue();
      //const qRes = await client.get("/admin/enrollment/pending");
      //setQueue(Array.isArray(qRes.data) ? qRes.data : qRes.data?.content || []);
      // FIX: Use 'data' instead of 'qRes.data'
            setQueue(Array.isArray(data) ? data : data?.content || []);
    } catch (e) {
      console.error("Enrollment fetch failed:", e);
      setQueue([]);
    } finally { setLoading(false); }
  };

  // 1. Fetch States on initial load
  useEffect(() => {
    load();
    getStates()
      .then(setLocStates)
      .catch(e => console.error("Failed to load states:", e.message));
  }, []);

  // 2. Fetch LGAs when a State is selected
  useEffect(() => {
    if (form.stateId) {
      getLgasByState(form.stateId)
        .then(setLgas)
        .catch(e => console.error("Failed to load LGAs:", e.message));
    } else {
      setLgas([]);
    }
  }, [form.stateId]);

  // 3. Fetch Polling Units when an LGA is selected
  useEffect(() => {
    if (form.lgaId) {
      setPuFetched(false);
      setPuError(null);
      getPollingUnitsByLga(form.lgaId)
        .then(data => { setPus(data); setPuFetched(true); })
        .catch(e => { setPuError(e.response?.data?.message || e.message || "Failed to load polling units"); setPuFetched(true); });
    } else {
      setPus([]); setPuFetched(false); setPuError(null);
    }
  }, [form.lgaId]);

// Automatically submit the form once step-up auth provides the headers
  useEffect(() => {
    if (queueHeaders) {
      handleQueue();
    }
  }, [queueHeaders]);

  const handleQueue = async () => {
      setSaving(true);
      try {
        // form.pollingUnit holds the real PU id (Long) returned by the backend.
        // parseInt is safe here — all IDs come directly from /api/locations/lgas/{id}/polling-units.
        const puId = parseInt(form.pollingUnit);

        const payload = {
          terminalId: form.terminalId,
          pollingUnitId: puId,
          voterPublicKey: "PENDING_FROM_TERMINAL",
          encryptedDemographic: "PENDING_BIOMETRIC_CAPTURE"
        };

// THE FIX: Pass the cryptographic headers to your wrapper!
        await queueEnrollment(payload, queueHeaders || {});
        //await client.post("/admin/enrollment/queue", payload, { headers: queueHeaders || {} });
        //setQueueHeaders(null);

        await load();
        setShowModal(false);
        setForm({ terminalId:"", stateId:"", lgaId:"", pollingUnit:"" });
        setQueueHeaders(null);
        showToast("Enrollment securely queued in database!");
      } catch (e) {
        showToast("Error: " + (e.response?.data?.error || e.message), "error");
      } finally { setSaving(false); }
    };

  const handleCancel = async (id) => {
    try {
      await client.delete(`/admin/enrollment/queue/${id}`);
      setQueue(p => p.filter(e => e.id !== id));
      showToast("Enrollment deleted from database");
    } catch (e) {
      showToast("Error: " + (e.response?.data?.error || e.message), "error");
    }
  };

  const handleRetry = async (record) => {
    setRetryingId(record.id);
    try {
      const payload = {
        terminalId: record.terminalId,
        // Use whichever field the backend returned — pollingUnitId is the Long on the DTO
        pollingUnitId: record.pollingUnitId || parseInt(record.pollingUnit) || null,
        voterPublicKey: "PENDING_FROM_TERMINAL",
        encryptedDemographic: "PENDING_BIOMETRIC_CAPTURE"
      };

      await client.post("/admin/enrollment/queue", payload);
      await load();
      showToast(`Successfully re-queued ${record.terminalId}`);
    } catch (e) {
      showToast("Retry failed: " + (e.response?.data?.error || e.message), "error");
    } finally {
      setRetryingId(null);
    }
  };

  const counts = {
    pending:   queue.filter(e=>e.status==="PENDING").length,
    completed: queue.filter(e=>e.status==="COMPLETED").length,
    failed:    queue.filter(e=>e.status==="FAILED").length,
  };

  return (
    <div className="p-7 flex flex-col gap-5">
      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                         px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                         ${toast.type==="error"?"text-danger":"text-purple-300"}`}>
          <Ic n={toast.type==="error"?"warning":"check"} s={15}/>
          {toast.msg}
          <button onClick={()=>setToast({msg:"",type:"success"})} className="text-muted hover:text-sub ml-1">
            <Ic n="close" s={12}/>
          </button>
        </div>
      )}

      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Pending"   value={counts.pending}   icon="enroll" accent="amber"  delay={0}/>
        <StatCard label="Completed" value={counts.completed} icon="check"  accent="green"  delay={50}/>
        <StatCard label="Failed"    value={counts.failed}    icon="close"  accent="red"    delay={100}/>
      </div>

      <div className="c-card p-6 animate-fade-up" style={{animationDelay:"180ms"}}>
        <SectionHeader
          title="Enrollment Queue"
          sub={`${queue.length} records · ${counts.pending} awaiting terminal`}
          action={
            <div className="flex gap-2">
              <button className="btn btn-surface btn-sm" onClick={load}><Ic n="refresh" s={13}/></button>
              <button className="btn btn-primary btn-sm" onClick={()=>setShowModal(true)}>
                <Ic n="plus" s={14} c="#fff" sw={2.5}/> Queue Voter
              </button>
            </div>
          }
        />

        <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
          style={{gridTemplateColumns:"110px 1fr 1fr 130px 65px 120px"}}>
          {["Terminal","Polling Unit","Election","Status","Time","Action"].map(h=>(
            <span key={h} className="sect-lbl">{h}</span>
          ))}
        </div>
        <hr className="divider mb-1"/>

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28}/></div>
        ) : queue.length === 0 ? (
          <EmptyState icon="enroll" title="Queue is empty" sub="Queue a voter enrollment to get started"/>
        ) : (
          queue.map((e, i) => (
            <div key={e.id} className="trow animate-fade-up"
              style={{gridTemplateColumns:"110px 1fr 1fr 130px 65px 120px",gap:"12px",animationDelay:`${i*25}ms`}}>
              <span className="mono text-[11px] font-medium text-purple-400">{e.terminalId}</span>
              <span className="text-xs font-semibold text-ink truncate">{e.pollingUnit || e.pollingUnitId}</span>
              <span className="text-xs text-sub truncate">{e.election || "Active Election"}</span>
              <StatusBadge status={e.status}/>
              <span className="mono text-[11px] text-muted">{e.createdAt ? new Date(e.createdAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'}) : "Just now"}</span>
              <div className="flex gap-1.5">

                {e.status==="FAILED" && (
                  <button
                    className="btn btn-ghost btn-sm !text-[11px] !px-2.5 !py-1.5 disabled:opacity-50"
                    onClick={() => handleRetry(e)}
                    disabled={retryingId === e.id}
                  >
                    {retryingId === e.id ? <Spinner s={11}/> : <Ic n="refresh" s={11}/>}
                    {retryingId === e.id ? "Retrying..." : "Retry"}
                  </button>
                )}

                {e.status==="PENDING" && (
                  <button className="btn btn-danger btn-sm !text-[11px] !px-2.5 !py-1.5"
                    onClick={()=>handleCancel(e.id)}>
                    <Ic n="close" s={11}/> Cancel
                  </button>
                )}
                {e.status==="COMPLETED" && (
                  <span className="flex items-center gap-1 text-[11px] font-bold text-success">
                    <Ic n="check" s={12} c="#34D399"/> Done
                  </span>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      {queueAuthModal}
      {showModal && (
        <Modal title="Queue Enrollment" onClose={()=>setShowModal(false)}>
          <div className="space-y-4">

            {/* Terminal ID */}
            <div>
              <Label>Terminal ID *</Label>
              <input
                className="inp inp-md font-mono uppercase" placeholder="e.g. TRM-016"
                value={form.terminalId}
                onChange={e => {
                  let val = e.target.value.toUpperCase().replace(/\s+/g, "");
                  if (val.startsWith("TRM") && !val.includes("-") && val.length > 3) {
                    val = val.slice(0, 3) + "-" + val.slice(3);
                  }
                  setForm(p => ({ ...p, terminalId: val }));
                }}
              />
            </div>

            {/* CASCADING LOCATION DROPDOWNS */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>State</Label>
                <select
                  className="inp inp-md"
                  value={form.stateId}
                  onChange={e => setForm(p => ({ ...p, stateId: e.target.value, lgaId: "", pollingUnit: "" }))}
                >
                  <option value="">Select State...</option>
                  {locStates.map(s => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                  ))}
                </select>
              </div>

              <div>
                <Label>LGA</Label>
                <select
                  className="inp inp-md disabled:opacity-50"
                  value={form.lgaId}
                  disabled={!form.stateId}
                  onChange={e => setForm(p => ({ ...p, lgaId: e.target.value, pollingUnit: "" }))}
                >
                  <option value="">Select LGA...</option>
                  {lgas.map(l => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </select>
              </div>
            </div>

            <div>
              <Label>Polling Unit *</Label>
              <select
                className="inp inp-md disabled:opacity-50"
                value={form.pollingUnit}
                disabled={!form.lgaId || !!puError || (puFetched && pus.length === 0)}
                onChange={e => setForm(p => ({ ...p, pollingUnit: e.target.value }))}
              >
                <option value="">
                  {!form.lgaId        ? "Select an LGA first"
                  : puError           ? "Error loading polling units"
                  : !puFetched        ? "Loading polling units…"
                  : pus.length === 0  ? "No polling units found for this LGA"
                  : "Select Polling Unit..."}
                </option>
                {pus.map(pu => (
                  <option key={pu.id} value={pu.id}>{pu.name} ({pu.code})</option>
                ))}
              </select>

              {/* Error: network/backend failure */}
              {puError && (
                <div className="mt-2 flex items-center gap-2 p-3 rounded-xl bg-red-500/10 border border-red-500/25">
                  <Ic n="warning" s={13} c="#F87171"/>
                  <span className="text-[11px] font-semibold text-danger">{puError}</span>
                  <button className="ml-auto text-[11px] font-bold text-purple-400 hover:text-purple-300 underline underline-offset-2"
                    onClick={() => {
                      setPuError(null); setPuFetched(false);
                      getPollingUnitsByLga(form.lgaId)
                        .then(data => { setPus(data); setPuFetched(true); })
                        .catch(e => { setPuError(e.message); setPuFetched(true); });
                    }}>Retry</button>
                </div>
              )}

              {/* Warning: LGA is in DB but has no polling units seeded yet */}
              {!puError && puFetched && pus.length === 0 && form.lgaId && (
                <div className="mt-2 flex items-start gap-2.5 p-3 rounded-xl bg-yellow-500/10 border border-yellow-500/25">
                  <Ic n="warning" s={13} c="#FCD34D"/>
                  <div>
                    <div className="text-xs font-bold text-warning">No polling units found for this LGA</div>
                    <div className="text-[11px] text-sub mt-0.5">
                      Use <span className="mono text-purple-300">POST /api/admin/polling-units</span> to add them first.
                    </div>
                  </div>
                </div>
              )}
            </div>



          </div>

          <div className="flex gap-3 mt-6">
            <button
              className="btn btn-primary btn-md flex-1 justify-center disabled:opacity-50"
              onClick={() => !queueHeaders ? queueWithAuth() : handleQueue()}
              disabled={saving || !form.terminalId || !form.pollingUnit}
            >
              {saving ? <Spinner s={16}/> : "Queue Enrollment →"}
            </button>
            <button className="btn btn-ghost btn-md flex-1 justify-center" onClick={()=>setShowModal(false)}>Cancel</button>
          </div>
        </Modal>
      )}
    </div>
  );
}