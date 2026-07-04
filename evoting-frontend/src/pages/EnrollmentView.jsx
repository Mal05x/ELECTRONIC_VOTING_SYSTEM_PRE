import { useState, useEffect, useCallback } from "react";
import React, { useState, useRef } from 'react';
import client from "../api/client.js";
import { getEnrollmentQueue, cancelEnrollment } from "../api/enrollment.js";
import { getPendingRegistrations, cancelPendingRegistration } from "../api/registration.js";
import { getStates, getLgasByState, getPollingUnitsByLga } from "../api/locations.js";
import { StatCard, StatusBadge, SectionHeader, Modal, Label, Spinner, EmptyState, Ic } from "../components/ui.jsx";
import { useStepUpAction } from "../components/StepUpModal.jsx";

// ── AdminTokenRevealModal (Kept perfectly intact for Security) ──────────────
function AdminTokenRevealModal({ enrollmentId, rawAdminToken, onAcknowledge }) {
  const [copied, setCopied] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(rawAdminToken).then(() => {
      setCopied(true); setTimeout(() => setCopied(false), 2500);
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
              This token is shown <strong className="text-ink">once only</strong> and cannot be recovered. 
              Copy and store it securely before proceeding.
            </p>
          </div>
        </div>
        <div className="rounded-xl bg-black border border-border-hi p-4">
          <div className="flex items-center justify-between mb-2">
            <span className="sect-lbl">Raw Admin Token (Base64)</span>
            <button onClick={handleCopy} className={`btn btn-sm ${copied ? "btn-success" : "btn-surface"} !text-[11px] !px-2.5`}>
              <Ic n={copied ? "check" : "copy"} s={11}/> {copied ? "Copied!" : "Copy"}
            </button>
          </div>
          <p className="font-mono text-[11px] text-green-300 break-all leading-relaxed select-all">{rawAdminToken}</p>
        </div>
        <label className="flex items-start gap-3 cursor-pointer group">
          <input type="checkbox" checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded accent-purple-500 flex-shrink-0" />
          <span className="text-xs text-sub group-hover:text-ink transition-colors">
            I have copied and securely stored the admin token. I understand it cannot be recovered.
          </span>
        </label>
        <button className="btn btn-primary btn-md justify-center disabled:opacity-40"
          disabled={!acknowledged} onClick={onAcknowledge}>
          {acknowledged ? "I've stored the token — continue" : "Tick the checkbox to continue"}
        </button>
      </div>
    </div>
  );
}

// ── EnrollmentView (The Unified Pipeline) ───────────────────────────────────
export default function EnrollmentView() {
  // Create a ref for the demographics section
  const demographicsRef = useRef(null);
  // We now track TWO lists: Raw Scans (Pending) and Active Hardware Jobs (Queue)
  const [scans, setScans] = useState([]); 
  const [queue, setQueue] = useState([]);
  
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState({ msg: "", type: "success" });
  const [tokenReveal, setTokenReveal] = useState(null);
  
  // Unified Wizard State
  const [wizardTarget, setWizardTarget] = useState(null);
  const [saving, setSaving] = useState(false);
  const [wizardHeaders, setWizardHeaders] = useState(null);

  // Location Data
  const [locStates, setLocStates] = useState([]);
  const [lgas, setLgas] = useState([]);
  const [pus, setPus] = useState([]);

  // The Unified Form (Demographics + Polling Unit combined!)
  const [form, setForm] = useState({
    firstName: "", surname: "", dateOfBirth: "", gender: "MALE",
    stateId: "", lgaId: "", pollingUnit: ""
  });

  const { trigger: enrollWithAuth, modal: enrollAuthModal } = useStepUpAction(
    "QUEUE_ENROLLMENT",
    () => `Authorize permanent registration for card ${wizardTarget?.cardIdHash?.slice(0,8)}...`,
    async (headers) => { setWizardHeaders(headers); }
  );

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4000);
  };

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      // Fetch both pipelines simultaneously
      const [queueData, scansData] = await Promise.all([
        getEnrollmentQueue().catch(() => []),
        getPendingRegistrations().catch(() => ({ pending: [] }))
      ]);
      setQueue(Array.isArray(queueData) ? queueData : queueData?.content || []);
      setScans(scansData.pending || []);
    } catch (e) {
      console.error("Pipeline fetch failed:", e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); getStates().then(setLocStates).catch(console.error); }, [loadData]);

  // Cascade Location Dropdowns
  useEffect(() => {
    if (form.stateId) getLgasByState(form.stateId).then(setLgas).catch(console.error);
    else setLgas([]);
  }, [form.stateId]);

  useEffect(() => {
    if (form.lgaId) getPollingUnitsByLga(form.lgaId).then(setPus).catch(console.error);
    else setPus([]);
  }, [form.lgaId]);

  // Watch for Step-Up Auth Success
  useEffect(() => { if (wizardHeaders) submitUnifiedEnrollment(); }, [wizardHeaders]);

  const openWizard = (scan) => {
    setWizardTarget(scan);
    setForm({ firstName: "", surname: "", dateOfBirth: "", gender: "MALE", stateId: "", lgaId: "", pollingUnit: "" });
    setWizardHeaders(null);

        // Scroll to the demographics section smoothly after the modal renders
    setTimeout(() => {
      demographicsRef.current?.scrollIntoView({ 
        behavior: 'smooth', 
        block: 'start' 
      });
    }, 100);
  };

  // ── THE PRODUCTION FIX: One single payload containing EVERYTHING ──
  const submitUnifiedEnrollment = async () => {
    setSaving(true);
    try {
      const payload = {
        pendingId: wizardTarget.pendingId,
        terminalId: wizardTarget.terminalId,
        cardIdHash: wizardTarget.cardIdHash,
        
        // Demographics
        firstName: form.firstName,
        surname: form.surname,
        dateOfBirth: form.dateOfBirth,
        gender: form.gender,

        // Location
        pollingUnitId: parseInt(form.pollingUnit)
      };

      // We hit the unified queue endpoint with the full dataset
      const result = await client.post("/admin/enrollment/unified-queue", payload, { headers: wizardHeaders }).then(r => r.data);
      
      await loadData();
      setWizardTarget(null);
      setWizardHeaders(null);

      if (result?.rawAdminToken) {
        setTokenReveal({ enrollmentId: result.enrollmentId, rawAdminToken: result.rawAdminToken });
      } else {
        showToast("Enrollment deployed to terminal.");
      }
    } catch (e) {
      showToast("Registration failed: " + (e.response?.data?.error || e.message), "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-7 flex flex-col gap-6">
      {toast.msg && (
        <div className={`fixed bottom-6 right-6 z-50 bg-card border border-border-hi rounded-2xl px-5 py-3.5 text-sm font-semibold shadow-card animate-slide-in flex items-center gap-3 ${toast.type === "error" ? "text-danger" : "text-purple-300"}`}>
          <Ic n={toast.type === "error" ? "warning" : "check"} s={15}/> {toast.msg}
        </div>
      )}

      {tokenReveal && (
        <AdminTokenRevealModal enrollmentId={tokenReveal.enrollmentId} rawAdminToken={tokenReveal.rawAdminToken}
          onAcknowledge={() => { setTokenReveal(null); showToast("Token acknowledged. Card personalization is active on terminal."); }} />
      )}

      <SectionHeader title="Registration Center" sub="Manage blank card scans and active hardware personalization queues." 
        action={<button className="btn btn-surface btn-sm" onClick={loadData}><Ic n="refresh" s={13}/> Refresh Pipeline</button>} />

      {/* ── STAGE 1: RAW SCANS (Awaiting Demographics) ── */}
      <div className="c-card p-6">
        <h3 className="text-sm font-bold text-white mb-4 flex items-center gap-2"><Ic n="chip" s={16} c="#A78BFA"/> Stage 1: Scanned Blank Cards</h3>
        {loading ? <Spinner s={24}/> : scans.length === 0 ? (
          <EmptyState icon="voters" title="No blank cards scanned" sub="Tap a blank J3R180 card on the terminal to begin." />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {scans.map(scan => (
              <div key={scan.pendingId} className="bg-elevated border border-border rounded-xl p-4 flex justify-between items-center">
                <div>
                  <div className="mono text-xs font-bold text-purple-400">{scan.cardIdHash}</div>
                  <div className="text-[11px] text-muted mt-1">Terminal: {scan.terminalId}</div>
                </div>
                <button className="btn btn-primary btn-sm" onClick={() => openWizard(scan)}>
                  Enroll Voter →
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── STAGE 2: ACTIVE HARDWARE QUEUE ── */}
      <div className="c-card p-6">
        <h3 className="text-sm font-bold text-white mb-4 flex items-center gap-2"><Ic n="lock" s={16} c="#34D399"/> Stage 2: Hardware Personalization Queue</h3>
        <div className="hidden xl:grid px-4 py-2 mb-1 gap-3" style={{ gridTemplateColumns: "110px 1fr 130px 65px" }}>
          {["Terminal", "Polling Unit", "Status", "Time"].map(h => <span key={h} className="sect-lbl">{h}</span>)}
        </div>
        <hr className="divider mb-1"/>
        {loading ? <Spinner s={24}/> : queue.length === 0 ? (
          <EmptyState icon="enroll" title="Queue is empty" sub="No active personalizations running."/>
        ) : (
          queue.map((e, i) => (
            <div key={e.id} className="trow animate-fade-up" style={{ gridTemplateColumns: "110px 1fr 130px 65px", gap: "12px", animationDelay: `${i * 25}ms` }}>
              <span className="mono text-[11px] font-medium text-purple-400">{e.terminalId}</span>
              <span className="text-xs font-semibold text-ink truncate">{e.pollingUnit || e.pollingUnitId}</span>
              <StatusBadge status={e.status}/>
              <span className="mono text-[11px] text-muted">{new Date(e.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
            </div>
          ))
        )}
      </div>

      {enrollAuthModal}

      {/* ── THE UNIFIED ENROLLMENT WIZARD MODAL ── */}
      {wizardTarget && (
        <Modal title="Complete Registration" onClose={() => !saving && setWizardTarget(null)}>
          {/* ATTACH THE REF HERE */}
          <div className="space-y-5" ref={demographicsRef}>
          <div className="space-y-5">
            <div className="bg-purple-500/10 border border-purple-500/20 rounded-xl p-3 flex items-center justify-between">
              <span className="text-xs text-purple-300">Terminal: <strong className="font-mono">{wizardTarget.terminalId}</strong></span>
              <span className="text-xs text-purple-300">Card: <strong className="font-mono">{wizardTarget.cardIdHash.slice(0,10)}...</strong></span>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div><Label>First Name *</Label><input className="inp inp-md" value={form.firstName} onChange={e => setForm(p => ({ ...p, firstName: e.target.value }))} /></div>
              <div><Label>Surname *</Label><input className="inp inp-md" value={form.surname} onChange={e => setForm(p => ({ ...p, surname: e.target.value }))} /></div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div><Label>Date of Birth *</Label><input type="date" className="inp inp-md" value={form.dateOfBirth} onChange={e => setForm(p => ({ ...p, dateOfBirth: e.target.value }))} /></div>
              <div>
                <Label>Gender</Label>
                <select className="inp inp-md" value={form.gender} onChange={e => setForm(p => ({ ...p, gender: e.target.value }))}>
                  <option value="MALE">Male</option><option value="FEMALE">Female</option>
                </select>
              </div>
            </div>

            <hr className="divider" />

            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>State</Label>
                <select className="inp inp-md" value={form.stateId} onChange={e => setForm(p => ({ ...p, stateId: e.target.value, lgaId: "", pollingUnit: "" }))}>
                  <option value="">Select State…</option>{locStates.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </div>
              <div>
                <Label>LGA</Label>
                <select className="inp inp-md disabled:opacity-50" disabled={!form.stateId} value={form.lgaId} onChange={e => setForm(p => ({ ...p, lgaId: e.target.value, pollingUnit: "" }))}>
                  <option value="">Select LGA…</option>{lgas.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
              </div>
            </div>
            
            <div>
              <Label>Assign Polling Unit *</Label>
              <select className="inp inp-md disabled:opacity-50" disabled={!form.lgaId || pus.length === 0} value={form.pollingUnit} onChange={e => setForm(p => ({ ...p, pollingUnit: e.target.value }))}>
                <option value="">{pus.length === 0 ? "Select LGA first" : "Select Target Polling Unit…"}</option>
                {pus.map(pu => <option key={pu.id} value={pu.id}>{pu.name}</option>)}
              </select>
            </div>
          </div>

          <div className="flex gap-3 mt-7">
            <button className="btn btn-primary btn-md flex-1 justify-center disabled:opacity-50"
              onClick={() => !wizardHeaders ? enrollWithAuth() : submitUnifiedEnrollment()}
              disabled={saving || !form.firstName || !form.surname || !form.dateOfBirth || !form.pollingUnit}>
              {saving ? <Spinner s={16}/> : "Submit & Personalize Card →"}
            </button>
            <button className="btn btn-ghost btn-md flex-1 justify-center" onClick={() => setWizardTarget(null)}>Cancel</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
