/**
 * ImportView — Bulk Candidate Import + Photo Management
 *
 * Tabs: JSON · CSV · Excel · TSV · Photos · Remove
 */
import { useState, useEffect, useRef, useCallback } from "react";
import { getElections, getCandidates, deleteCandidate, uploadCandidatePhoto } from "../api/elections.js";
import {
  importCandidatesJson,
  importCandidatesCsv,
  importCandidatesExcel,
  importCandidatesTsv,
} from "../api/imports.js";
import { SectionHeader, Spinner, Ic } from "../components/ui.jsx";
import { useStepUpAction } from "../components/StepUpModal.jsx";

// ── Tab definitions ───────────────────────────────────────────────────────────
const TABS = [
  { id: "json",   label: "JSON",   icon: "audit"   },
  { id: "csv",    label: "CSV",    icon: "check"   },
  { id: "excel",  label: "Excel",  icon: "tally"   },
  { id: "tsv",    label: "TSV",    icon: "audit"   },
  { id: "photos", label: "Photos", icon: "voters"  },
  { id: "remove", label: "Remove", icon: "warning" },
];

// ── Templates ─────────────────────────────────────────────────────────────────
const TEMPLATE_JSON = `[
  {
    "fullName": "Alhaji Musa Ibrahim",
    "partyAbbreviation": "APC",
    "position": "Governor"
  },
  {
    "fullName": "Dr Aminu Bello",
    "partyAbbreviation": "PDP",
    "position": "Governor"
  }
]`;

const CSV_TEMPLATE = `fullName,partyAbbreviation,position
Alhaji Musa Ibrahim,APC,Governor
Dr Aminu Bello,PDP,Governor`;

// ── Shared sub-components ─────────────────────────────────────────────────────

function ToastBar({ msg, type, onClose }) {
  if (!msg) return null;
  const styles = {
    error:   "border-red-500/30 text-danger",
    warning: "border-yellow-500/30 text-yellow-300",
    success: "border-green-500/30 text-success",
  };
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                     rounded-2xl border bg-card shadow-card text-sm font-semibold
                     animate-slide-in ${styles[type] || styles.success}`}>
      <Ic n={type === "error" ? "warning" : type === "warning" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="ml-1 text-muted hover:text-sub">
        <Ic n="close" s={12} />
      </button>
    </div>
  );
}

function ResultPanel({ result, onClose }) {
  if (!result) return null;
  return (
    <div className="c-card p-5 border border-purple-500/20 animate-fade-up">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-bold text-ink">Import Result</span>
        <button onClick={onClose} className="text-muted hover:text-sub">
          <Ic n="close" s={14} />
        </button>
      </div>
      <div className="grid grid-cols-3 gap-3 mb-3">
        {[
          { label: "Imported", value: result.imported ?? 0, color: "text-success" },
          { label: "Failed",   value: result.failed   ?? 0, color: "text-danger"  },
          { label: "Total",    value: (result.imported ?? 0) + (result.failed ?? 0), color: "text-ink" },
        ].map(s => (
          <div key={s.label} className="bg-elevated rounded-xl p-3 text-center">
            <div className={`text-2xl font-extrabold mono ${s.color}`}>{s.value}</div>
            <div className="text-[10px] text-muted font-semibold uppercase tracking-wider mt-1">
              {s.label}
            </div>
          </div>
        ))}
      </div>
      {result.errors?.length > 0 && (
        <div className="bg-red-500/8 border border-red-500/20 rounded-xl p-3 max-h-40 overflow-y-auto">
          <div className="text-xs font-bold text-danger mb-2">Errors</div>
          {result.errors.map((e, i) => (
            <div key={i} className="text-[11px] text-sub font-mono mb-1">• {e}</div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── File drop zone ─────────────────────────────────────────────────────────────
function DropZone({ file, accept, onFile, onError, accentColor = "purple", label }) {
  const ref = useRef(null);
  const accent = {
    purple: { border: "border-purple-500/40", bg: "bg-purple-500/5", hover: "hover:border-purple-500/30", icon: "#A78BFA" },
    green:  { border: "border-green-500/40",  bg: "bg-green-500/5",  hover: "hover:border-green-500/20",  icon: "#34D399" },
  }[accentColor] || {};

  return (
    <div
      className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer transition-colors
                  ${file ? `${accent.border} ${accent.bg}` : `border-border ${accent.hover} bg-elevated`}`}
      onClick={() => ref.current?.click()}
      onDragOver={e => e.preventDefault()}
      onDrop={e => {
        e.preventDefault();
        const f = e.dataTransfer.files[0];
        if (f) onFile(f);
        else if (onError) onError("Could not read dropped file");
      }}>
      <input ref={ref} type="file" accept={accept} className="hidden"
        onChange={e => { if (e.target.files[0]) onFile(e.target.files[0]); }} />
      <Ic n={file ? "check" : "enroll"} s={28} c={file ? accent.icon : "#4A4464"} />
      <div className="text-sm font-semibold mt-3" style={{ color: file ? accent.icon : "#6B7280" }}>
        {file ? file.name : label}
      </div>
      {file && <div className="text-xs text-muted mt-1">{(file.size / 1024).toFixed(1)} KB</div>}
    </div>
  );
}

// ── Photos Tab ────────────────────────────────────────────────────────────────
function PhotosTab({ electionId, showToast }) {
  const [candidates,    setCandidates]    = useState([]);
  const [loading,       setLoading]       = useState(false);
  const [uploading,     setUploading]     = useState(null);   // candidateId
  const [dragOver,      setDragOver]      = useState(null);   // candidateId
  const fileInputRefs   = useRef({});

  const load = useCallback(() => {
    if (!electionId) { setCandidates([]); return; }
    setLoading(true);
    getCandidates(electionId)
      .then(d => setCandidates(Array.isArray(d) ? d : []))
      .catch(() => setCandidates([]))
      .finally(() => setLoading(false));
  }, [electionId]);

  useEffect(() => { load(); }, [load]);

  const doUpload = async (candidateId, file) => {
    // Validate
    if (!file.type.startsWith("image/")) {
      showToast("File must be JPEG, PNG, or WebP", "error"); return;
    }
    if (file.size > 5_242_880) {
      showToast("Image must be under 5 MB", "error"); return;
    }
    setUploading(candidateId);
    try {
      const res = await uploadCandidatePhoto(candidateId, file);
      setCandidates(prev =>
        prev.map(c => c.id === candidateId ? { ...c, imageUrl: res.imageUrl } : c)
      );
      showToast("Photo uploaded ✓");
    } catch (e) {
      const msg = e.response?.data?.error || e.message || "Upload failed";
      if (msg.includes("S3") || msg.includes("AWS") || msg.includes("bucket")) {
        showToast("S3 not configured — set AWS_ACCESS_KEY_ID on backend", "error");
      } else {
        showToast(msg, "error");
      }
    } finally { setUploading(null); }
  };

  const handleFileInput = (candidateId, e) => {
    const file = e.target.files?.[0];
    if (file) doUpload(candidateId, file);
    e.target.value = "";
  };

  const handleDrop = (candidateId, e) => {
    e.preventDefault();
    setDragOver(null);
    const file = e.dataTransfer.files?.[0];
    if (file) doUpload(candidateId, file);
  };

  const withPhoto    = candidates.filter(c => c.imageUrl);
  const withoutPhoto = candidates.filter(c => !c.imageUrl);

  if (!electionId) {
    return (
      <div className="c-card p-12 flex flex-col items-center gap-3 text-center animate-fade-up">
        <Ic n="voters" s={32} c="#2A2A4A" />
        <p className="text-sm text-muted">Select an election above to manage candidate photos</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5 animate-fade-up">

      {/* How-to banner */}
      <div className="c-card p-4 flex items-start gap-3 border border-purple-500/15">
        <Ic n="shield" s={16} c="#A78BFA" className="mt-0.5 flex-shrink-0" />
        <div className="text-xs text-sub leading-relaxed">
          <span className="font-bold text-purple-300">How to upload: </span>
          Click the photo circle or drag-and-drop an image onto any candidate card.
          Accepted formats: <span className="mono text-purple-300">JPEG, PNG, WebP</span> · Max 5 MB per photo.
          Photos appear immediately in the Tally View face chart and candidate standings.
          Requires <span className="mono text-purple-300">AWS_ACCESS_KEY_ID</span> configured on the backend.
        </div>
      </div>

      {/* Progress summary */}
      {candidates.length > 0 && (
        <div className="grid grid-cols-3 gap-4">
          {[
            { label: "Total Candidates", value: candidates.length,    color: "text-ink" },
            { label: "Photos Uploaded",  value: withPhoto.length,     color: "text-success" },
            { label: "Missing Photos",   value: withoutPhoto.length,  color: withoutPhoto.length > 0 ? "text-warning" : "text-muted" },
          ].map(s => (
            <div key={s.label} className="c-card p-4 text-center">
              <div className={`text-3xl font-extrabold mono ${s.color}`}>{s.value}</div>
              <div className="text-[11px] text-muted font-semibold uppercase tracking-wider mt-1">{s.label}</div>
              {s.label === "Photos Uploaded" && candidates.length > 0 && (
                <div className="mt-2 h-1.5 bg-elevated rounded-full overflow-hidden">
                  <div className="h-full bg-success rounded-full transition-all"
                    style={{ width: `${(withPhoto.length / candidates.length) * 100}%` }} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Candidate grid */}
      {loading ? (
        <div className="c-card flex justify-center py-16"><Spinner s={32} /></div>
      ) : candidates.length === 0 ? (
        <div className="c-card p-12 flex flex-col items-center gap-3 text-center">
          <Ic n="voters" s={32} c="#2A2A4A" />
          <p className="text-sm text-muted">No candidates in this election yet.</p>
          <p className="text-xs text-muted">Import candidates first using the JSON, CSV, or Excel tabs.</p>
        </div>
      ) : (
        <div className="c-card p-5">
          {/* Section dividers */}
          {withoutPhoto.length > 0 && (
            <div className="mb-5">
              <div className="flex items-center gap-2 mb-3">
                <span className="text-xs font-bold text-warning uppercase tracking-wide">
                  Missing Photos ({withoutPhoto.length})
                </span>
                <div className="flex-1 h-px bg-warning/20" />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
                {withoutPhoto.map(c => (
                  <CandidatePhotoCard
                    key={c.id}
                    candidate={c}
                    uploading={uploading}
                    dragOver={dragOver}
                    onDragOver={setDragOver}
                    onDrop={handleDrop}
                    onFileInput={handleFileInput}
                    fileInputRefs={fileInputRefs}
                  />
                ))}
              </div>
            </div>
          )}

          {withPhoto.length > 0 && (
            <div>
              <div className="flex items-center gap-2 mb-3">
                <span className="text-xs font-bold text-success uppercase tracking-wide">
                  Photos Uploaded ({withPhoto.length})
                </span>
                <div className="flex-1 h-px bg-success/20" />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
                {withPhoto.map(c => (
                  <CandidatePhotoCard
                    key={c.id}
                    candidate={c}
                    uploading={uploading}
                    dragOver={dragOver}
                    onDragOver={setDragOver}
                    onDrop={handleDrop}
                    onFileInput={handleFileInput}
                    fileInputRefs={fileInputRefs}
                  />
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Single candidate photo card ───────────────────────────────────────────────
function CandidatePhotoCard({ candidate: c, uploading, dragOver, onDragOver, onDrop, onFileInput, fileInputRefs }) {
  const isUploading = uploading === c.id;
  const isDragOver  = dragOver  === c.id;
  const hasPhoto    = !!c.imageUrl;

  return (
    <div
      className={`relative rounded-2xl border transition-all overflow-hidden
                  ${isDragOver
                    ? "border-purple-500/60 bg-purple-500/10 scale-[1.02]"
                    : hasPhoto
                      ? "border-green-500/20 bg-card"
                      : "border-dashed border-border bg-elevated hover:border-purple-500/30"}`}
      onDragOver={e => { e.preventDefault(); onDragOver(c.id); }}
      onDragLeave={() => onDragOver(null)}
      onDrop={e => onDrop(c.id, e)}>

      {/* Photo area */}
      <div
        className="relative flex items-center justify-center cursor-pointer group"
        style={{ height: 160, background: hasPhoto ? "#0a0a14" : "transparent" }}
        onClick={() => fileInputRefs.current[c.id]?.click()}>

        {isUploading ? (
          <div className="flex flex-col items-center gap-2">
            <Spinner s={28} />
            <span className="text-xs text-muted">Uploading…</span>
          </div>
        ) : hasPhoto ? (
          <>
            <img
              src={c.imageUrl}
              alt={c.fullName}
              className="w-full h-full object-cover opacity-90 group-hover:opacity-70 transition-opacity"
              onError={e => { e.target.style.display = "none"; }}
            />
            {/* Replace overlay on hover */}
            <div className="absolute inset-0 flex flex-col items-center justify-center
                            opacity-0 group-hover:opacity-100 transition-opacity
                            bg-black/60 gap-2">
              <Ic n="refresh" s={20} c="#A78BFA" />
              <span className="text-xs font-bold text-purple-300">Replace Photo</span>
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center gap-2 py-6">
            <div className="w-16 h-16 rounded-full border-2 border-dashed border-border
                            flex items-center justify-center group-hover:border-purple-500/50
                            transition-colors">
              <Ic n="plus" s={20} c="#4A4464" />
            </div>
            <span className="text-xs text-muted group-hover:text-sub transition-colors">
              {isDragOver ? "Drop to upload" : "Click or drag photo"}
            </span>
          </div>
        )}

        <input
          ref={el => { fileInputRefs.current[c.id] = el; }}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={e => onFileInput(c.id, e)}
        />
      </div>

      {/* Candidate info */}
      <div className="px-3 py-2.5 border-t border-border/50">
        <div className="text-xs font-bold text-ink truncate">{c.fullName}</div>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="badge badge-purple text-[9px]">{c.party || c.partyAbbreviation}</span>
          <span className="text-[10px] text-muted truncate">{c.position}</span>
        </div>
        {hasPhoto && (
          <div className="flex items-center gap-1 mt-1">
            <span className="text-[9px] text-success font-semibold">✓ Photo set</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Main Component ─────────────────────────────────────────────────────────────
export default function ImportView() {
  const [elections,   setElections]   = useState([]);
  const [electionId,  setElectionId]  = useState("");
  const [activeTab,   setActiveTab]   = useState("json");
  const [saving,      setSaving]      = useState(false);
  const [result,      setResult]      = useState(null);
  const [toast,       setToast]       = useState({ msg: "", type: "success" });

  // JSON tab
  const [jsonText,    setJsonText]    = useState(TEMPLATE_JSON);

  // File tabs
  const [file,        setFile]        = useState(null);
  const fileRef = useRef(null);

  // Remove tab
  const [candidates,  setCandidates]  = useState([]);
  const [candLoading, setCandLoading] = useState(false);
  const [confirmDel,  setConfirmDel]  = useState(null);
  const [deleting,    setDeleting]    = useState(null);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4500);
  };

  // Step-up auth for import
  const { trigger: importWithAuth, modal: importModal, pending: importPending } =
    useStepUpAction(
      "IMPORT_CANDIDATES",
      () => {
        const name = elections.find(e => e.id === electionId)?.name || "election";
        if (activeTab === "json") {
          try {
            const a = JSON.parse(jsonText);
            return `Import ${Array.isArray(a) ? a.length : "?"} candidates into "${name}"`;
          } catch { return `Import candidates into "${name}"`; }
        }
        return `Import ${file?.name || "file"} into "${name}"`;
      },
      async (headers) => {
        if (!electionId) { showToast("Select an election first", "error"); return; }
        if (activeTab === "json") {
          const parsed = JSON.parse(jsonText);
          const res = await importCandidatesJson(electionId, parsed, headers);
          setResult(res);
          showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
        } else {
          const fn = { csv: importCandidatesCsv, excel: importCandidatesExcel, tsv: importCandidatesTsv }[activeTab];
          const res = await fn(electionId, file, headers);
          setResult(res);
          showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
        }
      }
    );

  // Step-up auth for delete
  const { trigger: deleteWithAuth, modal: deleteModal } =
    useStepUpAction(
      "DELETE_CANDIDATE",
      () => confirmDel ? `Remove "${confirmDel.fullName}" from this election` : "Remove candidate",
      async (headers) => {
        if (!confirmDel) return;
        setDeleting(confirmDel.id);
        try {
          await deleteCandidate(confirmDel.id, headers);
          setCandidates(p => p.filter(c => c.id !== confirmDel.id));
          showToast(`${confirmDel.fullName} removed`);
        } finally {
          setDeleting(null);
          setConfirmDel(null);
        }
      }
    );

  // Load elections
  useEffect(() => {
    getElections()
      .then(data => {
        setElections(data);
        const active = data.find(e => e.status === "ACTIVE") || data[0];
        if (active) setElectionId(active.id);
      })
      .catch(() => {});
  }, []);

  // Reset file when switching tabs
  useEffect(() => {
    setFile(null);
    setResult(null);
    if (fileRef.current) fileRef.current.value = "";
  }, [activeTab]);

  // Load candidates for remove tab
  useEffect(() => {
    if (activeTab !== "remove" || !electionId) { setCandidates([]); return; }
    setCandLoading(true);
    getCandidates(electionId)
      .then(d => setCandidates(Array.isArray(d) ? d : []))
      .catch(() => setCandidates([]))
      .finally(() => setCandLoading(false));
  }, [activeTab, electionId]);

  const downloadTemplate = () => {
    const content = activeTab === "csv" ? CSV_TEMPLATE : TEMPLATE_JSON;
    const ext     = activeTab === "csv" ? "csv" : "json";
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([content], { type: "text/plain" }));
    a.download = `candidates-template.${ext}`;
    a.click();
  };

  const fileMeta = {
    csv:   { accept: ".csv",       label: "Click or drag & drop a CSV file here",   accent: "purple" },
    tsv:   { accept: ".tsv,.txt",  label: "Click or drag & drop a TSV file here",   accent: "purple" },
    excel: { accept: ".xlsx,.xls", label: "Click or drag & drop an Excel file here", accent: "green"  },
  };

  return (
    <div className="p-7 flex flex-col gap-5">

      <ToastBar msg={toast.msg} type={toast.type}
        onClose={() => setToast({ msg: "", type: "success" })} />

      <SectionHeader
        title="Import Candidates"
        sub="Bulk-load candidates from JSON, CSV or Excel · Manage photos · Remove candidates" />

      {/* Election selector */}
      <div className="c-card px-5 py-4 flex items-center gap-4">
        <label className="text-xs font-bold text-sub uppercase tracking-wider whitespace-nowrap">
          Election
        </label>
        <select className="inp inp-md flex-1 max-w-sm"
          value={electionId} onChange={e => setElectionId(e.target.value)}>
          <option value="">— select an election —</option>
          {elections.map(e => (
            <option key={e.id} value={e.id}>{e.name} — {e.status}</option>
          ))}
        </select>
      </div>

      {/* Tab bar */}
      <div className="flex gap-2 flex-wrap">
        {TABS.map(t => (
          <button key={t.id}
            className={`btn btn-sm gap-2 transition-all
              ${activeTab === t.id
                ? t.id === "photos" ? "bg-purple-500/20 border border-purple-500/40 text-purple-300"
                  : t.id === "remove" ? "bg-red-500/15 border border-red-500/30 text-danger"
                  : "btn-primary"
                : "btn-ghost"}`}
            onClick={() => setActiveTab(t.id)}>
            <Ic n={t.icon} s={13} />
            {t.label}
          </button>
        ))}
      </div>

      {/* ── JSON ── */}
      {activeTab === "json" && (
        <div className="c-card p-6 flex flex-col gap-4 animate-fade-up">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-bold text-ink">Paste JSON Array</div>
              <div className="text-xs text-muted mt-0.5">Array of objects with fullName, partyAbbreviation, position</div>
            </div>
            <button className="btn btn-ghost btn-sm gap-1.5" onClick={downloadTemplate}>
              <Ic n="check" s={13} /> Download Template
            </button>
          </div>
          <textarea
            className="inp font-mono text-xs leading-relaxed resize-none"
            style={{ minHeight: 280, background: "#0d0d1a", color: "#CDD6F4" }}
            value={jsonText}
            onChange={e => setJsonText(e.target.value)}
            spellCheck={false}
          />
          <div className="flex items-center justify-between pt-1">
            <span className="text-xs text-muted">
              {(() => {
                try {
                  const a = JSON.parse(jsonText);
                  return Array.isArray(a) ? `${a.length} candidates detected` : "Not an array";
                } catch { return "Invalid JSON"; }
              })()}
            </span>
            <button className="btn btn-primary btn-md gap-2"
              onClick={importWithAuth} disabled={importPending || !electionId}>
              {importPending ? <Spinner s={16} /> : <Ic n="check" s={15} c="#fff" />}
              Import Candidates
            </button>
          </div>
        </div>
      )}

      {/* ── CSV / TSV / Excel (shared drop zone) ── */}
      {["csv", "tsv", "excel"].includes(activeTab) && (
        <div className="c-card p-6 flex flex-col gap-5 animate-fade-up">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-bold text-ink">
                Upload {activeTab.toUpperCase()} File
              </div>
              <div className="text-xs text-muted mt-0.5">
                {activeTab === "excel"
                  ? ".xlsx or .xls — first row must be headers"
                  : `Columns: fullName, partyAbbreviation, position`}
              </div>
            </div>
            {activeTab === "csv" && (
              <button className="btn btn-ghost btn-sm gap-1.5" onClick={downloadTemplate}>
                <Ic n="check" s={13} /> Download Template
              </button>
            )}
          </div>
          <DropZone
            file={file}
            accept={fileMeta[activeTab].accept}
            label={fileMeta[activeTab].label}
            accentColor={fileMeta[activeTab].accent}
            onFile={setFile}
            onError={msg => showToast(msg, "error")}
          />
          <div className="flex justify-end">
            <button className="btn btn-primary btn-md gap-2"
              onClick={importWithAuth} disabled={importPending || !file || !electionId}>
              {importPending ? <Spinner s={16} /> : <Ic n="check" s={15} c="#fff" />}
              Import {activeTab.toUpperCase()}
            </button>
          </div>
        </div>
      )}

      {/* ── Photos ── */}
      {activeTab === "photos" && (
        <PhotosTab electionId={electionId} showToast={showToast} />
      )}

      {/* ── Remove ── */}
      {activeTab === "remove" && (
        <div className="c-card p-6 flex flex-col gap-4 animate-fade-up">
          <div>
            <div className="text-sm font-bold text-ink">Remove Candidates</div>
            <div className="text-xs text-muted mt-0.5">
              Removing a candidate is permanent and audit-logged. Requires step-up authentication.
            </div>
          </div>

          {!electionId ? (
            <div className="py-8 text-center text-xs text-muted">Select an election above</div>
          ) : candLoading ? (
            <div className="flex justify-center py-10"><Spinner s={28} /></div>
          ) : candidates.length === 0 ? (
            <div className="py-8 text-center text-xs text-muted">No candidates in this election</div>
          ) : (
            <div className="flex flex-col gap-2">
              {candidates.map(c => (
                <div key={c.id}
                  className="flex items-center gap-3 px-4 py-3 rounded-xl bg-elevated
                             border border-border hover:border-red-500/20 transition-colors">
                  {/* Photo thumbnail */}
                  {c.imageUrl ? (
                    <img src={c.imageUrl} alt={c.fullName}
                      className="w-9 h-9 rounded-full object-cover flex-shrink-0 border border-border"
                      //onError={e => { e.target.style.display="none"; }}
                       />
                  ) : (
                    <div className="w-9 h-9 rounded-full bg-elevated border border-dashed border-border
                                    flex items-center justify-center flex-shrink-0">
                      <Ic n="voters" s={13} c="#4A4464" />
                    </div>
                  )}
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-bold text-ink truncate">{c.fullName}</div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="badge badge-purple text-[9px]">{c.party || c.partyAbbreviation}</span>
                      <span className="text-[10px] text-muted">{c.position}</span>
                    </div>
                  </div>
                  {confirmDel?.id === c.id ? (
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <span className="text-[11px] text-danger font-semibold">Confirm remove?</span>
                      <button
                        className="btn btn-sm bg-red-500/20 border border-red-500/40
                                   text-danger hover:bg-red-500/30 rounded-xl px-3 text-xs font-bold"
                        onClick={() => deleteWithAuth()}
                        disabled={deleting === c.id}>
                        {deleting === c.id ? <Spinner s={12} /> : "Yes, Remove"}
                      </button>
                      <button className="btn btn-surface btn-sm text-xs"
                        onClick={() => setConfirmDel(null)}>
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      className="btn btn-sm border border-red-500/20 text-danger
                                 bg-transparent hover:bg-red-500/10 rounded-xl px-3 text-xs
                                 flex-shrink-0 transition-colors"
                      onClick={() => setConfirmDel(c)}>
                      <Ic n="close" s={12} c="#F87171" /> Remove
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <ResultPanel result={result} onClose={() => setResult(null)} />

      {/* Column reference */}
      {["json","csv","excel","tsv"].includes(activeTab) && (
        <div className="c-card p-5">
          <div className="text-xs font-bold text-sub uppercase tracking-wider mb-3">
            Required columns for all formats
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            {[
              { field: "fullName",          req: true,  eg: "Alhaji Musa Ibrahim" },
              { field: "partyAbbreviation", req: false, eg: "APC" },
              { field: "position",          req: true,  eg: "Governor" },
            ].map(c => (
              <div key={c.field} className="bg-elevated rounded-xl p-3">
                <div className="flex items-center gap-2 mb-1">
                  <span className="mono text-[12px] text-purple-400">{c.field}</span>
                  {c.req
                    ? <span className="badge badge-purple text-[9px]">required</span>
                    : <span className="text-[9px] text-muted border border-border rounded px-1.5 py-0.5">optional</span>}
                </div>
                <div className="text-[11px] text-muted">e.g. {c.eg}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {importModal}
      {deleteModal}
    </div>
  );
}
