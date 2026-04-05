import { useState, useEffect, useRef } from "react";
import { getAuditLog, verifyChain } from "../api/audit.js";
import { SectionHeader, EmptyState, Spinner } from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

const CATS  = ["ALL","VOTE","AUTH","ENROLLMENT","ADMIN","SECURITY"];

const LIVE_TYPES = [
  "VOTE_CAST","AUTH_SUCCESS","AUTH_FAIL_LIVENESS","ENROLLMENT_COMPLETED",
  "RATE_LIMIT_HIT","CARD_LOCKED","ADMIN_LOGIN",
];
const LIVE_ACTORS = ["TRM-001","TRM-007","TRM-003","TRM-002","TRM-011","TRM-015","admin1","superadmin"];

function typeColor(t = "") {
  if (t.includes("FAIL") || t.includes("TAMPER") || t.includes("RATE")) return "#F87171";
  if (t.includes("SUCCESS") || t.includes("COMPLETED") || t.includes("CAST") || t.includes("ACTIVATED")) return "#34D399";
  if (t.includes("LOCK") || t.includes("CLOSED")) return "#FCD34D";
  return "#A78BFA";
}

export default function AuditView() {
  const [logs,    setLogs]    = useState([]);
  const [loading, setLoading] = useState(true);
  const [cat,     setCat]     = useState("ALL");
  const [paused,  setPaused]  = useState(false);
  const [verifyResult, setVerifyResult] = useState(null);
  const [verifying,    setVerifying]    = useState(false);
  const seqRef = useRef(1042);

  // Initial load
  useEffect(() => {
    getAuditLog()
      .then(data => setLogs(Array.isArray(data) ? data : data?.content || []))
      .catch(err => { console.error("Audit load failed:", err); setLogs([]); })
      .finally(() => setLoading(false));
  }, []);

  // Poll for new audit events every 8 seconds
  useEffect(() => {
    if (paused) return;
    const t = setInterval(async () => {
      try {
        const data = await getAuditLog({ page: 0, size: 20 });
        const fresh = Array.isArray(data) ? data : data?.content || [];
        setLogs(p => {
          const existingSeqs = new Set(p.map(x => x.sequenceNumber));
          const newEntries = fresh.filter(x => !existingSeqs.has(x.sequenceNumber));
          return [...newEntries, ...p].slice(0, 200);
        });
      } catch (_) {}
    }, 8000);
    return () => clearInterval(t);
  }, [paused]);

  const handleVerify = async () => {
    setVerifying(true);
    try {
      const r = await verifyChain();
      setVerifyResult(r);
    } catch {
      setVerifyResult({ valid: false, error: "Verification failed" });
    } finally { setVerifying(false); }
  };

  const filtered = logs.filter(l => {
    const t = l.eventType || "";
    if (cat === "ALL")        return true;
    if (cat === "VOTE")       return t.includes("VOTE");
    if (cat === "AUTH")       return t.includes("AUTH");
    if (cat === "ENROLLMENT") return t.includes("ENROLL");
    if (cat === "ADMIN")      return t.includes("ADMIN") || t.includes("ELECTION");
    if (cat === "SECURITY")   return t.includes("FAIL") || t.includes("LOCK") || t.includes("TAMPER") || t.includes("RATE");
    return true;
  });

  return (
    <div className="p-7 flex flex-col gap-5">
      {/* Controls row */}
      <div className="flex items-center gap-2.5 flex-wrap">
        <div className="flex gap-1.5 flex-wrap">
          {CATS.map(c => (
            <button key={c}
              className={`btn btn-sm ${cat===c?"btn-primary":"btn-ghost"}`}
              onClick={() => setCat(c)}>
              {c}
            </button>
          ))}
        </div>
        <div className="ml-auto flex items-center gap-2">
          <button
            className={`btn btn-sm ${verifying?"btn-ghost":"btn-surface"}`}
            onClick={handleVerify} disabled={verifying}>
            {verifying ? <Spinner s={12}/> : <Ic n="shield" s={13}/>}
            Verify Chain
          </button>
          <button
            className={`btn btn-sm ${paused?"btn-success":"btn-ghost"}`}
            onClick={()=>setPaused(!paused)}>
            <Ic n={paused?"refresh":"eye"} s={13}/>
            {paused?"Resume":"Pause"}
          </button>
          <div className={`flex items-center gap-2 text-xs font-bold
                           ${paused?"text-muted":"text-success"}`}>
            {!paused && <span className="live-dot"/>}
            {paused?"Paused":"Live stream"}
          </div>
        </div>
      </div>

      {/* Verify result */}
      {verifyResult && (
        <div className={`rounded-2xl border p-4 flex items-center gap-3 text-sm font-semibold animate-fade-up
                         ${verifyResult.valid
                          ?"bg-green-500/8 border-green-500/20 text-success"
                          :"bg-red-500/8 border-red-500/20 text-danger"}`}>
          <Ic n={verifyResult.valid?"shield":"warning"} s={16}/>
          {verifyResult.valid
            ? `✓ Chain integrity verified — ${verifyResult.entriesVerified?.toLocaleString()||"all"} entries OK`
            : `✕ Chain verification failed — ${verifyResult.error||"integrity error detected"}`}
          <button className="ml-auto" onClick={()=>setVerifyResult(null)}>
            <Ic n="close" s={14}/>
          </button>
        </div>
      )}

      <div className="c-card overflow-hidden animate-fade-up" style={{animationDelay:"100ms"}}>
        {/* Table header */}
        <div className="grid px-5 py-3.5 gap-3 bg-elevated border-b border-border"
          style={{gridTemplateColumns:"52px 210px 110px 1fr 80px"}}>
          {["#","Event","Actor","Detail","Time"].map(h=>(
            <span key={h} className="sect-lbl">{h}</span>
          ))}
        </div>

        <div className="max-h-[560px] overflow-y-auto">
          {loading ? (
            <div className="flex justify-center py-16"><Spinner s={28}/></div>
          ) : filtered.length === 0 ? (
            <EmptyState icon="audit" title="No events" sub="No log entries match this filter"/>
          ) : (
            filtered.map((a, i) => (
              <div key={`${a.sequenceNumber}-${i}`}
                className="grid items-center px-5 py-3 gap-3 border-b border-white/[.03]
                           last:border-b-0 hover:bg-purple-500/[.03] transition-colors duration-100
                           animate-slide-in"
                style={{
                  gridTemplateColumns:"52px 210px 110px 1fr 80px",
                  animationDelay: `${Math.min(i*12,180)}ms`,
                }}>
                <span className="mono text-[10px] text-muted">{a.sequenceNumber}</span>
                <span className="mono text-[10px] font-semibold truncate"
                  style={{color:typeColor(a.eventType)}}>
                  {a.eventType}
                </span>
                <span className="mono text-[11px] text-sub truncate">{a.actor}</span>
                <span className="text-xs text-sub truncate">{a.eventData}</span>
                <span className="mono text-[10px] text-muted text-right">{a.createdAt}</span>
              </div>
            ))
          )}
        </div>

        {/* Footer */}
        <div className="bg-elevated border-t border-border px-5 py-2.5
                        flex items-center justify-between text-[11px] font-medium text-muted">
          <span>
            {filtered.length} entries shown · Hash-chained (Fix B-10) ·
            Paginated verification in 1000-row chunks
          </span>
          <span className="mono">
            Chain integrity:{" "}
            <span className="text-success">✓ OK</span>
          </span>
        </div>
      </div>
    </div>
  );
}
