/**
 * ImportView — Bulk Candidate Import
 * Supports three methods: manual JSON entry, CSV upload, Excel upload.
 * Wired to ElectionImportController endpoints.
 */
import { useState, useEffect, useRef } from "react";
import { getElections, getCandidates, deleteCandidate } from "../api/elections.js";
import {
  importCandidatesJson,
  importCandidatesCsv,
  importCandidatesExcel,
  importCandidatesTsv,
} from "../api/imports.js";
import { SectionHeader, Spinner, Ic } from "../components/ui.jsx";
import { useStepUpAction } from "../components/StepUpModal.jsx";

const TABS = [
  { id: "json",  label: "JSON",  icon: "audit"  },
  { id: "csv",   label: "CSV",   icon: "check"  },
  { id: "excel", label: "Excel", icon: "tally"  },
  { id: "tsv",   label: "TSV",   icon: "audit"  },
  { id: "remove", label: "Remove", icon: "warning" }, // <-- ADD THIS LINE!
];

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

function ToastBar({ msg, type, onClose }) {
  if (!msg) return null;
  const styles = {
    error:   "border-red-500/30 text-danger",
    warning: "border-yellow-500/30 text-yellow-300",
    success: "border-green-500/30 text-success",
  };
  const icons = { error: "warning", warning: "warning", success: "check" };
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                     rounded-2xl border bg-card shadow-card text-sm font-semibold
                     animate-slide-in ${styles[type] || styles.success}`}>
      <Ic n={icons[type] || "check"} s={15} />
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

function CandidateRow({ candidate, onReload }) {
  const [isDeleting, setIsDeleting] = useState(false);

  // 1. Set up the Cryptographic Wrapper
  const { trigger: deleteWithAuth, modal: authModal } = useStepUpAction(
    "DELETE_CANDIDATE", // Must match your Java StepUpAuthService & ACTION_META
    () => `Are you sure you want to delete ${candidate.fullName}? This cannot be undone.`,
    async (headers) => {
      setIsDeleting(true);
      try {
        await deleteCandidate(candidate.id, headers);
        // Refresh your list after successful deletion!
        if (onReload) onReload();
      } catch (error) {
        console.error("Delete failed:", error);
        // Add your toast notification here!
      } finally {
        setIsDeleting(false);
      }
    }
  );

  return (
    <div className="flex items-center justify-between p-3 border-b border-border">
      {/* Candidate Info */}

      <div>
        <div className="font-bold text-ink">{candidate.fullName}</div>
        <div className="text-xs text-sub">{candidate.partyAbbreviation}</div>
      </div>

      {/* 2. The Delete Button */}
      <button
        onClick={deleteWithAuth} // <-- Triggers the Step-Up Modal!
        disabled={isDeleting}
        className="btn btn-ghost text-danger hover:bg-red-500/10 px-3 py-1.5 rounded-lg flex items-center gap-2"
      >
        {isDeleting ? <Spinner s={14} /> : <Ic n="warning" s={14} />}
        Delete
      </button>

      {/* 3. Don't forget to render the modal anywhere in the component! */}
      {authModal}
    </div>
  );
}

export default function ImportView() {
  const [elections,   setElections]   = useState([]);
  const [electionId,  setElectionId]  = useState("");
  const [activeTab,   setActiveTab]   = useState("json");
  const [saving,      setSaving]      = useState(false);
  const [result,      setResult]      = useState(null);
  const [toast,       setToast]       = useState({ msg: "", type: "success" });

  // JSON tab
  const [jsonText,    setJsonText]    = useState(TEMPLATE_JSON);

  // Remove tab
  const [candidates,  setCandidates]  = useState([]);
  const [candLoading, setCandLoading] = useState(false);
  const [deleting,    setDeleting]    = useState(null);
  const [confirmDel,  setConfirmDel]  = useState(null); // candidate to confirm delete

  // CSV / Excel tab
  const [file,        setFile]        = useState(null);
  const fileRef = useRef(null);

  // Step-up auth
  const { trigger: importWithAuth, modal: importModal, pending: importPending } =
    useStepUpAction(
      "IMPORT_CANDIDATES",
      () => {
        const elecName = elections.find(e => e.id === electionId)?.name || "selected election";
        if (activeTab === "json") {
          try { const a = JSON.parse(jsonText); return `Import ${Array.isArray(a) ? a.length : "?"} candidates into "${elecName}"`; }
          catch { return `Import candidates into "${elecName}"`; }
        }
        return `Import ${file?.name || "file"} into "${elecName}"`;
      },
      async (headers) => {
        if (activeTab === "json") {
          const parsed = JSON.parse(jsonText);
          const res = await importCandidatesJson(electionId, parsed, headers);
          setResult(res);
          showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
        } else {
          const fn = activeTab === "csv" ? importCandidatesCsv : importCandidatesExcel;
          const res = await fn(electionId, file, headers);
          setResult(res);
          showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
        }
      }
    );

  const { trigger: deleteWithAuth, modal: deleteModal } =
    useStepUpAction(
      "DELETE_CANDIDATE",
      () => confirmDel ? `Remove "${confirmDel.fullName}" from this election` : "Remove candidate",
      async (headers) => {
        if (!confirmDel) return;
        await deleteCandidate(confirmDel.id, headers);
        setCandidates(p => p.filter(c => c.id !== confirmDel.id));
        setConfirmDel(null);
        showToast(`${confirmDel.fullName} removed`);
      }
    );

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 4000);
  };

  useEffect(() => {
    getElections()
      .then(data => {
        setElections(data);
        const active = data.find(e => e.status === "ACTIVE") || data[0];
        if (active) setElectionId(active.id);
      })
      .catch(() => {});
  }, []);

  // Reset file when tab changes
  useEffect(() => {
    setFile(null);
    setResult(null);
    if (fileRef.current) fileRef.current.value = "";
  }, [activeTab]);

  // Load candidates when on remove tab
  useEffect(() => {
    if (activeTab !== "remove" || !electionId) { setCandidates([]); return; }
    setCandLoading(true);
    getCandidates(electionId)
      .then(data => setCandidates(Array.isArray(data) ? data : []))
      .catch(() => setCandidates([]))
      .finally(() => setCandLoading(false));
  }, [activeTab, electionId]);

  const handleRemove = async (candidate) => {
    setDeleting(candidate.id);
    try {
      await deleteCandidate(candidate.id);
      setCandidates(p => p.filter(c => c.id !== candidate.id));
      setConfirmDel(null);
      showToast(`${candidate.fullName} removed from election`);
    } catch (e) {
      showToast(e.response?.data?.error || "Remove failed", "error");
    } finally { setDeleting(null); }
  };

  const handleJson = async () => {
    if (!electionId) { showToast("Select an election first", "error"); return; }
    let parsed;
    try { parsed = JSON.parse(jsonText); }
    catch { showToast("Invalid JSON — check the format", "error"); return; }
    if (!Array.isArray(parsed)) {
      showToast("JSON must be an array of candidates", "error"); return;
    }
    setSaving(true);
    try {
      const res = await importCandidatesJson(electionId, parsed);
      setResult(res);
      showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
    } catch (e) {
      showToast(e.response?.data?.error || "Import failed", "error");
    } finally { setSaving(false); }
  };

  const handleFile = async () => {
    if (!electionId) { showToast("Select an election first", "error"); return; }
    if (!file) { showToast("Select a file to upload", "error"); return; }
    setSaving(true);
    try {
      const fn = activeTab === "csv" ? importCandidatesCsv
               : activeTab === "tsv"  ? importCandidatesTsv
               : importCandidatesExcel;
      const res = await fn(electionId, file);
      setResult(res);
      showToast(`Imported ${res.imported} candidates`, res.failed > 0 ? "warning" : "success");
    } catch (e) {
      showToast(e.response?.data?.error || "Import failed", "error");
    } finally { setSaving(false); }
  };

  const downloadTemplate = () => {
    const content = activeTab === "csv" ? CSV_TEMPLATE : TEMPLATE_JSON;
    const ext      = activeTab === "csv" ? "csv" : "json";
    const blob = new Blob([content], { type: "text/plain" });
    const a    = document.createElement("a");
    a.href     = URL.createObjectURL(blob);
    a.download = `candidates-template.${ext}`;
    a.click();
  };

  return (
    <div className="p-7 flex flex-col gap-5">

      <ToastBar msg={toast.msg} type={toast.type}
        onClose={() => setToast({ msg: "", type: "success" })} />

      <SectionHeader
        title="Import Candidates"
        sub="Bulk-load candidates from JSON, CSV, or Excel into an election" />

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

      {/* Tab selector */}
      <div className="flex gap-2">
        {TABS.map(t => (
          <button key={t.id}
            className={`btn btn-sm gap-2 ${activeTab === t.id ? "btn-primary" : "btn-ghost"}`}
            onClick={() => setActiveTab(t.id)}>
            <Ic n={t.icon} s={13} />
            {t.label}
          </button>
        ))}
      </div>

      {/* ── JSON Tab ── */}
      {activeTab === "json" && (
        <div className="c-card p-6 flex flex-col gap-4 animate-fade-up">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-bold text-ink">Paste JSON Array</div>
              <div className="text-xs text-muted mt-0.5">
                Array of objects with fullName, partyAbbreviation, position
              </div>
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
                try { const a = JSON.parse(jsonText); return Array.isArray(a) ? `${a.length} candidates detected` : "Not an array"; }
                catch { return "Invalid JSON"; }
              })()}
            </span>
            <button className="btn btn-primary btn-md gap-2"
              onClick={importWithAuth} disabled={importPending || !electionId}>
              {saving ? <Spinner s={16} /> : <Ic n="check" s={15} c="#fff" />}
              Import Candidates
            </button>
          </div>
        </div>
      )}

      {/* ── CSV Tab ── */}
      {activeTab === "csv" && (
        <div className="c-card p-6 flex flex-col gap-5 animate-fade-up">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-bold text-ink">Upload CSV File</div>
              <div className="text-xs text-muted mt-0.5">
                Columns: fullName, partyAbbreviation, position
              </div>
            </div>
            <button className="btn btn-ghost btn-sm gap-1.5" onClick={downloadTemplate}>
              <Ic n="check" s={13} /> Download Template
            </button>
          </div>

          <div
            className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer
                        transition-colors
                        ${file
                          ? "border-purple-500/40 bg-purple-500/5"
                          : "border-border hover:border-purple-500/30 bg-elevated"}`}
            onClick={() => fileRef.current?.click()}
            onDragOver={e => e.preventDefault()}
            onDrop={e => {
              e.preventDefault();
              const f = e.dataTransfer.files[0];
              if (f && f.name.endsWith(".csv")) setFile(f);
              else showToast("Please drop a .csv file", "error");
            }}>
            <input ref={fileRef} type="file"
            accept={activeTab === "csv" ? ".csv" : activeTab === "tsv" ? ".tsv,.txt" : ".xlsx,.xls"}
            className="hidden"
              onChange={e => setFile(e.target.files[0] || null)} />
            <Ic n={file ? "check" : "enroll"} s={28}
              c={file ? "#A78BFA" : "#4A4464"} />
            <div className="text-sm font-semibold mt-3"
              style={{ color: file ? "#A78BFA" : "#6B7280" }}>
              {file ? file.name : "Click or drag & drop a CSV file here"}
            </div>
            {file && (
              <div className="text-xs text-muted mt-1">
                {(file.size / 1024).toFixed(1)} KB
              </div>
            )}
          </div>

          <div className="flex justify-end">
            <button className="btn btn-primary btn-md gap-2"
              onClick={importWithAuth} disabled={importPending || !file || !electionId}>
              {importPending ? <Spinner s={16} /> : <Ic n="check" s={15} c="#fff" />}
              Import CSV
            </button>
          </div>
        </div>
      )}

      {/* ── Excel Tab ── */}
      {activeTab === "excel" && (
        <div className="c-card p-6 flex flex-col gap-5 animate-fade-up">
          <div>
            <div className="text-sm font-bold text-ink">Upload Excel File</div>
            <div className="text-xs text-muted mt-0.5">
              .xlsx or .xls — first row must be headers: fullName, partyAbbreviation, position
            </div>
          </div>

          <div
            className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer
                        transition-colors
                        ${file
                          ? "border-green-500/40 bg-green-500/5"
                          : "border-border hover:border-green-500/20 bg-elevated"}`}
            onClick={() => fileRef.current?.click()}
            onDragOver={e => e.preventDefault()}
            onDrop={e => {
              e.preventDefault();
              const f = e.dataTransfer.files[0];
              if (f && (f.name.endsWith(".xlsx") || f.name.endsWith(".xls"))) setFile(f);
              else showToast("Please drop an .xlsx or .xls file", "error");
            }}>
            <input ref={fileRef} type="file" accept=".xlsx,.xls" className="hidden"
              onChange={e => setFile(e.target.files[0] || null)} />
            <Ic n={file ? "check" : "tally"} s={28}
              c={file ? "#34D399" : "#4A4464"} />
            <div className="text-sm font-semibold mt-3"
              style={{ color: file ? "#34D399" : "#6B7280" }}>
              {file ? file.name : "Click or drag & drop an Excel file here"}
            </div>
            {file && (
              <div className="text-xs text-muted mt-1">
                {(file.size / 1024).toFixed(1)} KB
              </div>
            )}
          </div>

          <div className="flex justify-end">
            <button className="btn btn-primary btn-md gap-2"
              onClick={importWithAuth} disabled={importPending || !file || !electionId}>
              {importPending ? <Spinner s={16} /> : <Ic n="check" s={15} c="#fff" />}
              Import Excel
            </button>
          </div>
        </div>
      )}

      {/* ── TSV Tab ── */}
      {activeTab === "tsv" && (
        <div className="c-card p-6 flex flex-col gap-5 animate-fade-up">
          <div>
            <div className="text-sm font-bold text-ink">Upload TSV File</div>
            <div className="text-xs text-muted mt-0.5">
              Tab-separated values — columns: fullName, partyAbbreviation, position
            </div>
          </div>
          <div
            className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer
                        transition-colors
                        ${file ? "border-purple-500/40 bg-purple-500/5" : "border-border hover:border-purple-500/30 bg-elevated"}`}
            onClick={() => fileRef.current?.click()}
            onDragOver={e => e.preventDefault()}
            onDrop={e => {
              e.preventDefault();
              const f = e.dataTransfer.files[0];
              if (f && (f.name.endsWith(".tsv") || f.name.endsWith(".txt"))) setFile(f);
              else showToast("Please drop a .tsv or .txt file", "error");
            }}>
            <input ref={fileRef} type="file" accept=".tsv,.txt" className="hidden"
              onChange={e => setFile(e.target.files[0] || null)} />
            <Ic n={file ? "check" : "audit"} s={28} c={file ? "#A78BFA" : "#4A4464"} />
            <div className="text-sm font-semibold mt-3" style={{ color: file ? "#A78BFA" : "#6B7280" }}>
              {file ? file.name : "Click or drag & drop a TSV file here"}
            </div>
            {file && <div className="text-xs text-muted mt-1">{(file.size/1024).toFixed(1)} KB</div>}
          </div>
          <div className="flex justify-end">
           {/* Change handleFile to importWithAuth inside the TSV tab */}
           <button className="btn btn-primary btn-md gap-2"
             onClick={importWithAuth} disabled={importPending || !file || !electionId}>
             {importPending ? <Spinner s={16}/> : <Ic n="check" s={15} c="#fff"/>} Import TSV
           </button>
          </div>
        </div>
      )}

      {/* Result panel */}
      <ResultPanel result={result} onClose={() => setResult(null)} />

      {/* ── REMOVE TAB ── */}
      {activeTab === "remove" && (
        <div className="c-card p-6 flex flex-col gap-4 animate-fade-up">
          <div>
            <div className="text-sm font-bold text-ink">Remove Candidates</div>
            <div className="text-xs text-muted mt-0.5">
              Removing a candidate immediately removes them from the live tally.
              This action is permanent and audit logged.
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
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-bold text-ink truncate">{c.fullName}</div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="badge badge-purple text-[9px]">{c.party}</span>
                      <span className="text-[10px] text-muted">{c.position}</span>
                    </div>
                  </div>
                  {confirmDel?.id === c.id ? (
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <span className="text-[11px] text-danger font-semibold">Confirm?</span>
                      <button
                        className="btn btn-sm bg-red-500/20 border border-red-500/40
                                   text-danger hover:bg-red-500/30 rounded-xl px-3 text-xs font-bold"
                        onClick={() => { setConfirmDel(c); deleteWithAuth(); }}
                        disabled={deleting === c.id}>
                        {deleting === c.id ? <Spinner s={12} /> : "Remove"}
                      </button>
                      <button
                        className="btn btn-surface btn-sm text-xs"
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

      {importModal}
      {deleteModal}
      {/* Format reference */}
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
                  : <span className="text-[9px] text-muted border border-border rounded px-1.5 py-0.5">optional</span>
                }
              </div>
              <div className="text-[11px] text-muted">e.g. {c.eg}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
