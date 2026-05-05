import { useState, useEffect, useRef } from "react";
import client from "../api/client.js";
import { getEnrollmentQueue, queueEnrollment, cancelEnrollment } from "../api/enrollment.js";
import { getElections } from "../api/elections.js";
import { getStates, getLgasByState, getPollingUnitsByLga } from "../api/locations.js";
import { StatCard, StatusBadge, SectionHeader, Modal, Label, Spinner, EmptyState } from "../components/ui.jsx";
import { useStepUpAction } from "../components/StepUpModal.jsx";
import { Ic } from "../components/ui.jsx";

// ── AdminTokenRevealModal ──────────────────────────────────────────────────────
// Shown exactly ONCE after a successful queueEnrollment.
// rawAdminToken is the 32-byte secret needed to decommission the card via
// INS_LOCK_CARD.  It is never stored in the DB and never returned by the API again.
// Dismissal requires an explicit acknowledgement checkbox.

function AdminTokenRevealModal({ enrollmentId, rawAdminToken, onAcknowledge }) {
  const [copied,       setCopied]       = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(rawAdminToken).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    });
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/80 backdrop-blur-sm">
      <div className="c-card w-full max-w-lg p-7 flex flex-col gap-5 shadow-2xl border-2 border-warning/50">

        <div className="flex items-start gap-3">
          <div className="mt-0.5 w-9 h-9 flex-shrink-0 rounded-full bg-warning/15 flex items-center justify-center">
            <Ic n="warning" s={18} c="#FCD34D"/>
          </div>
          <div>
            <h2 className="text-base font-bold text-warning">One-Time Admin Token</h2>
            <p className="text-xs text-sub mt-1">
              This token is shown <strong className="text-ink">once only</strong> and cannot
              be recovered. Copy and store it securely before proceeding.
            </p>
          </div>
        </div>

        <div className="rounded-xl bg-surface border border-border-hi p-4 text-xs text-sub space-y-1.5">
          <div className="flex items-center gap-2">
            <Ic n="info" s={12} c="#818CF8"/>
            <span className="font-semibold text-purple-300">What this token does</span>
          </div>
          <p>
            This 32-byte token is written as its SHA-256 hash into the JCOP4 card during
            enrollment. If the card must ever be <strong className="text-ink">permanently
            decommissioned</strong>, the terminal needs this raw token to issue
            <code className="font-mono text-purple-300 mx-1">INS_LOCK_CARD</code>.
          </p>
          <p className="text-muted">
            Enrollment ID: <span className="font-mono text-purple-300">{enrollmentId}</span>
          </p>
        </div>

        <div className="rounded-xl bg-black border border-border-hi p-4">
          <div className="flex items-center justify-between mb-2">
            <span className="sect-lbl">Raw Admin Token (Base64)</span>
            <button
              onClick={handleCopy}
              className={`btn btn-sm ${copied ? "btn-success" : "btn-surface"} !text-[11px] !px-2.5`}
            >
              <Ic n={copied ? "check" : "copy"} s={11}/>
              {copied ? "Copied!" : "Copy"}
            </button>
          </div>
          <p className="font-mono text-[11px] text-green-300 break-all leading-relaxed select-all">
            {rawAdminToken}
          </p>
        </div>

        <div className="rounded-xl bg-danger/10 border border-danger/30 p-3 text-xs text-danger space-y-1">
          <div className="font-bold flex items-center gap-1.5">
            <Ic n="warning" s={12} c="#F87171"/> Do not close until you have:
          </div>
          <ul className="list-disc list-inside space-y-0.5 text-[11px] text-sub pl-1">
            <li>Copied the token to a password manager or HSM</li>
            <li>Or printed it and stored it in a sealed physical register</li>
            <li>Loss of this token means the card <strong className="text-danger">cannot</strong> be administratively locked</li>
          </ul>
        </div>

        <label className="flex items-start gap-3 cursor-pointer group">
          <input
            type="checkbox"
            checked={acknowledged}
            onChange={e => setAcknowledged(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded accent-purple-500 flex-shrink-0"
          />
          <span className="text-xs text-sub group-hover:text-ink transition-colors">
            I have copied and securely stored the admin token for enrollment{" "}
            <span className="font-mono text-purple-300">{enrollmentId}</span>.
            I understand it cannot be recovered from the system.
          </span>
        </label>

        <button
          className="btn btn-primary btn-md justify-center disabled:opacity-40 disabled:cursor-not-allowed"
          disabled={!acknowledged}
          onClick={onAcknowledge}
        >
          {acknowledged ? "I've stored the token — continue" : "Tick the checkbox to continue"}
        </button>
      </div>
    </div>
  );
}

// ── EnrollmentView ────────────────────────────────────────────────────────────

export default function EnrollmentView() {
  const [queue,        setQueue]        = useState([]);
  const [loading,      setLoading]      = useState(true);
  const [showModal,    setShowModal]    = useState(false);
  const [queueHeaders, setQueueHeaders] = useState(null);
  const [saving,       setSaving]       = useState(false);
  const [retryingId,   setRetryingId]   = useState(null);
  const [toast,        setToast]        = useState({ msg: "", type: "success" });
  const [tokenReveal,  setTokenReveal]  = useState(null);
  const [elections,    setElections]    = useState([]);
  const [locStates,    setLocStates]    = useState([]);
  const [lgas,         setLgas]         = useState([]);
  const [pus,          setPus]          = useState([]);
  const [puFetched,    setPuFetched]    = useState(false);
  const [puError,      setPuError]      = useState(null);

  const [form, setForm] = useState({
    terminalId: "", electionId: "", stateId: "", lgaId: "", pollingUnit: "",
  });

  const { trigger: queueWithAuth, modal: queueAuthModal } = useStepUpAction(
    "QUEUE_ENROLLMENT",
    () => `Queue enrollment for terminal ${form.terminalId || "?"}`,
    async (headers) => { setQueueHeaders(headers); }
  );

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  const load = async () => {
    setLoading(true);
    try {
      const data = await getEnrollmentQueue();
      setQueue(Array.isArray(data) ? data : data?.content || []);
    } catch (e) {
      console.error("Enrollment fetch failed:", e);
      setQueue([]);
    } finally { setLoading(false); }
  };

  useEffect(() => {
    load();
    getStates()
      .then(setLocStates)
      .catch(e => console.error("States:", e.message));
    getElections()
      .then(data => setElections(Array.isArray(data) ? data : data?.content || []))
      .catch(e => console.error("Elections:", e.message));
  }, []);

  useEffect(() => {
    if (form.stateId) {
      getLgasByState(form.stateId).then(setLgas).catch(console.error);
    } else { setLgas([]); }
  }, [form.stateId]);

  useEffect(() => {
    if (form.lgaId) {
      setPuFetched(false); setPuError(null);
      getPollingUnitsByLga(form.lgaId)
        .then(data => { setPus(data); setPuFetched(true); })
        .catch(e => { setPuError(e.response?.data?.message || e.message); setPuFetched(true); });
    } else { setPus([]); setPuFetched(false); setPuError(null); }
  }, [form.lgaId]);

  useEffect(() => { if (queueHeaders) handleQueue(); }, [queueHeaders]);

  // ── handleQueue ────────────────────────────────────────────────────────────
  const handleQueue = async () => {
    setSaving(true);
    try {
      const payload = {
        terminalId:           form.terminalId,
        pollingUnitId:        parseInt(form.pollingUnit),
        electionId:           form.electionId || undefined,
        voterPublicKey:       "PENDING_FROM_TERMINAL",
        encryptedDemographic: "PENDING_BIOMETRIC_CAPTURE",
      };

      // CRITICAL: capture the response — rawAdminToken is only returned here
      const result = await queueEnrollment(payload, queueHeaders || {});

      await load();
      setShowModal(false);
      setForm({ terminalId: "", electionId: "", stateId: "", lgaId: "", pollingUnit: "" });
      setQueueHeaders(null);

      if (result?.rawAdminToken) {
        // Show one-time reveal modal — toast fires only AFTER admin acknowledges
        setTokenReveal({ enrollmentId: result.enrollmentId, rawAdminToken: result.rawAdminToken });
      } else {
        showToast("Enrollment queued. WARNING: server did not return admin token. Upgrade backend to V19.");
      }
    } catch (e) {
      showToast("Error: " + (e.response?.data?.error || e.message), "error");
    } finally { setSaving(false); }
  };

  const handleCancel = async (id) => {
    try {
      await cancelEnrollment(id);
      setQueue(p => p.filter(e => e.id !== id));
      showToast("Enrollment cancelled");
    } catch (e) {
      showToast("Error: " + (e.response?.data?.error || e.message), "error");
    }
  };

  // ── handleRetry — captures new token on retry ──────────────────────────────
  const handleRetry = async (record) => {
    setRetryingId(record.id);
    try {
      const payload = {
        terminalId:           record.terminalId,
        pollingUnitId:        record.pollingUnitId || parseInt(record.pollingUnit) || null,
        electionId:           record.electionId    || undefined,
        voterPublicKey:       "PENDING_FROM_TERMINAL",
        encryptedDemographic: "PENDING_BIOMETRIC_CAPTURE",
      };
      const result = await client.post("/admin/enrollment/queue", payload).then(r => r.data);
      await load();
      if (result?.rawAdminToken) {
        setTokenReveal({ enrollmentId: result.enrollmentId, rawAdminToken: result.rawAdminToken });
      } else {
        showToast(`Re-queued ${record.terminalId}`);
      }
    } catch (e) {
      showToast("Retry failed: " + (e.response?.data?.error || e.message), "error");
    } finally { setRetryingId(null); }
  };

  const counts = {
    pending:   queue.filter(e => e.status === "PENDING").length,
    completed: queue.filter(e => e.status === "COMPLETED").length,
    failed:    queue.filter(e => e.status === "FAILED").length,
  };

  return (
    <div className="p-7 flex flex-col gap-5">

      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl
                         px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3
                         ${toast.type === "error" ? "text-danger" : "text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15}/>
          {toast.msg}
          <button onClick={() => setToast({ msg: "", type: "success" })} className="text-muted hover:text-sub ml-1">
            <Ic n="close" s={12}/>
          </button>
        </div>
      )}

      {/* One-time token reveal — rendered above all other content */}
      {tokenReveal && (
        <AdminTokenRevealModal
          enrollmentId={tokenReveal.enrollmentId}
          rawAdminToken={tokenReveal.rawAdminToken}
          onAcknowledge={() => {
            setTokenReveal(null);
            showToast("Enrollment queued and token acknowledged.");
          }}
        />
      )}

      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Pending"   value={counts.pending}   icon="enroll" accent="amber" delay={0}/>
        <StatCard label="Completed" value={counts.completed} icon="check"  accent="green" delay={50}/>
        <StatCard label="Failed"    value={counts.failed}    icon="close"  accent="red"   delay={100}/>
      </div>

      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "180ms" }}>
        <SectionHeader
          title="Enrollment Queue"
          sub={`${queue.length} records · ${counts.pending} awaiting terminal`}
          action={
            <div className="flex gap-2">
              <button className="btn btn-surface btn-sm" onClick={load}><Ic n="refresh" s={13}/></button>
              <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}>
                <Ic n="plus" s={14} c="#fff" sw={2.5}/> Queue Voter
              </button>
            </div>
          }
        />

        <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
          style={{ gridTemplateColumns: "110px 1fr 1fr 130px 65px 120px" }}>
          {["Terminal", "Polling Unit", "Election", "Status", "Time", "Action"].map(h => (
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
              style={{ gridTemplateColumns: "110px 1fr 1fr 130px 65px 120px", gap: "12px", animationDelay: `${i * 25}ms` }}>
              <span className="mono text-[11px] font-medium text-purple-400">{e.terminalId}</span>
              <span className="text-xs font-semibold text-ink truncate">{e.pollingUnit || e.pollingUnitId}</span>
              <span className="text-xs text-sub truncate">{e.election || "—"}</span>
              <StatusBadge status={e.status}/>
              <span className="mono text-[11px] text-muted">
                {e.createdAt
                  ? new Date(e.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
                  : "Just now"}
              </span>
              <div className="flex gap-1.5">
                {e.status === "FAILED" && (
                  <button
                    className="btn btn-ghost btn-sm !text-[11px] !px-2.5 !py-1.5 disabled:opacity-50"
                    onClick={() => handleRetry(e)}
                    disabled={retryingId === e.id}
                  >
                    {retryingId === e.id ? <Spinner s={11}/> : <Ic n="refresh" s={11}/>}
                    {retryingId === e.id ? "Retrying…" : "Retry"}
                  </button>
                )}
                {e.status === "PENDING" && (
                  <button className="btn btn-danger btn-sm !text-[11px] !px-2.5 !py-1.5"
                    onClick={() => handleCancel(e.id)}>
                    <Ic n="close" s={11}/> Cancel
                  </button>
                )}
                {e.status === "COMPLETED" && (
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
        <Modal title="Queue Enrollment" onClose={() => setShowModal(false)}>
          <div className="space-y-4">

            <div>
              <Label>Terminal ID *</Label>
              <input
                className="inp inp-md font-mono uppercase"
                placeholder="e.g. TERM-KD-001"
                value={form.terminalId}
                onChange={e => setForm(p => ({ ...p, terminalId: e.target.value.toUpperCase().replace(/\s+/g, "") }))}
              />
            </div>

            <div>
              <Label>Election *</Label>
              <select
                className="inp inp-md"
                value={form.electionId}
                onChange={e => setForm(p => ({ ...p, electionId: e.target.value }))}
              >
                <option value="">Select Election…</option>
                {elections.map(el => (
                  <option key={el.id} value={el.id}>{el.title || el.name}</option>
                ))}
              </select>
              <p className="text-[11px] text-muted mt-1">
                The election this card will vote in. The applet scopes the voted flag per election.
              </p>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>State</Label>
                <select className="inp inp-md" value={form.stateId}
                  onChange={e => setForm(p => ({ ...p, stateId: e.target.value, lgaId: "", pollingUnit: "" }))}>
                  <option value="">Select State…</option>
                  {locStates.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </div>
              <div>
                <Label>LGA</Label>
                <select className="inp inp-md disabled:opacity-50" value={form.lgaId}
                  disabled={!form.stateId}
                  onChange={e => setForm(p => ({ ...p, lgaId: e.target.value, pollingUnit: "" }))}>
                  <option value="">Select LGA…</option>
                  {lgas.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
              </div>
            </div>

            <div>
              <Label>Polling Unit *</Label>
              <select className="inp inp-md disabled:opacity-50" value={form.pollingUnit}
                disabled={!form.lgaId || !!puError || (puFetched && pus.length === 0)}
                onChange={e => setForm(p => ({ ...p, pollingUnit: e.target.value }))}>
                <option value="">
                  {!form.lgaId ? "Select an LGA first" : puError ? "Error loading" :
                   !puFetched ? "Loading…" : pus.length === 0 ? "No polling units" : "Select Polling Unit…"}
                </option>
                {pus.map(pu => <option key={pu.id} value={pu.id}>{pu.name} ({pu.code})</option>)}
              </select>
              {puError && (
                <div className="mt-2 flex items-center gap-2 p-3 rounded-xl bg-red-500/10 border border-red-500/25">
                  <Ic n="warning" s={13} c="#F87171"/>
                  <span className="text-[11px] font-semibold text-danger">{puError}</span>
                  <button className="ml-auto text-[11px] font-bold text-purple-400 underline"
                    onClick={() => { setPuError(null); setPuFetched(false);
                      getPollingUnitsByLga(form.lgaId)
                        .then(d => { setPus(d); setPuFetched(true); })
                        .catch(e => { setPuError(e.message); setPuFetched(true); }); }}>Retry</button>
                </div>
              )}
            </div>

            {/* Admin token warning */}
            <div className="rounded-xl bg-warning/10 border border-warning/30 p-3 text-[11px] text-warning flex items-start gap-2">
              <Ic n="warning" s={12} c="#FCD34D" className="mt-0.5 flex-shrink-0"/>
              <span>
                After queuing, a <strong>one-time admin token</strong> will appear.
                Store it securely — it is required to permanently decommission this card
                and cannot be recovered from the system.
              </span>
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
            <button className="btn btn-ghost btn-md flex-1 justify-center" onClick={() => setShowModal(false)}>
              Cancel
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
