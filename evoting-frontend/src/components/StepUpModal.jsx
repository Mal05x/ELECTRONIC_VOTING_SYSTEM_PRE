/**
 * StepUpModal — ECDSA step-up authentication prompt.
 *
 * Shown before any sensitive action. Provides a clear summary of
 * what is about to happen, requests the user to authorize with
 * their signing key, and handles error states gracefully.
 *
 * Usage:
 *   import { withStepUp } from "./StepUpModal.jsx";
 *
 *   const handleImport = withStepUp(
 *     "IMPORT_CANDIDATES",
 *     "Import 3 candidates into 2027 Presidential Election",
 *     async (headers) => {
 *       await importCandidatesJson(electionId, candidates, headers);
 *     }
 *   );
 */
import { useState, useCallback } from "react";
import { Ic, Spinner }           from "./ui.jsx";
import { useStepUpAuth }         from "../hooks/useStepUpAuth.js";
import { useKeypair }            from "../context/KeypairContext.jsx";

const ACTION_META = {
  IMPORT_CANDIDATES:  { icon: "tally",   color: "purple", label: "Import Candidates"    },
  DELETE_CANDIDATE:   { icon: "warning", color: "red",    label: "Delete Candidate"      },
  QUEUE_ENROLLMENT:   { icon: "enroll",  color: "blue",   label: "Queue Enrollment"      },
  COMMIT_REGISTRATION:{ icon: "voters",  color: "green",  label: "Complete Registration" },
  CREATE_ELECTION:    { icon: "ballot",  color: "purple", label: "Create Election"        },
  CREATE_PARTY:       { icon: "flag",    color: "amber",  label: "Create Party"           },
};

const COLOR_MAP = {
  purple: { bg: "bg-purple-500/10", border: "border-purple-500/20", text: "text-purple-400", icon: "#A78BFA" },
  red:    { bg: "bg-red-500/10",    border: "border-red-500/20",    text: "text-red-400",    icon: "#F87171" },
  blue:   { bg: "bg-blue-500/10",   border: "border-blue-500/20",   text: "text-blue-400",   icon: "#60A5FA" },
  green:  { bg: "bg-green-500/10",  border: "border-green-500/20",  text: "text-green-400",  icon: "#34D399" },
  amber:  { bg: "bg-amber-500/10",  border: "border-amber-500/20",  text: "text-amber-400",  icon: "#FCD34D" },
};

/**
 * StepUpModal — the actual modal component.
 * Typically not used directly — use withStepUp() wrapper instead.
 */
export function StepUpModal({ actionType, summary, onAuthorize, onCancel }) {
  const { hasLocalKey, needsSetup } = useKeypair();
  const { requireAuth }             = useStepUpAuth();
  const [step,  setStep]  = useState("prompt"); // prompt | signing | done | error | nokey
  const [error, setError] = useState("");

  const meta   = ACTION_META[actionType] || { icon: "shield", color: "purple", label: actionType };
  const colors = COLOR_MAP[meta.color] || COLOR_MAP.purple;

  const handleAuthorize = async () => {
    if (!hasLocalKey || needsSetup) {
      setStep("nokey");
      return;
    }
    setStep("signing");
    try {
      const headers = await requireAuth(actionType, { summary, force: true });
      if (!headers) {
        setError("Authorization cancelled or signing failed.");
        setStep("error");
        return;
      }
      if (headers.__noKeypair) {
        setStep("nokey");
        return;
      }
      setStep("done");
      setTimeout(() => onAuthorize(headers), 400);
    } catch (e) {
      setError(e.response?.data?.error || e.message || "Authorization failed");
      setStep("error");
    }
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-6
                    bg-black/75 backdrop-blur-sm"
         onClick={e => e.target === e.currentTarget && step === "prompt" && onCancel()}>

      <div className="w-full max-w-md bg-card border border-border-hi rounded-2xl
                      shadow-2xl animate-fade-up overflow-hidden">

        {/* Header */}
        <div className={`p-5 border-b border-border flex items-center gap-4 ${colors.bg}`}>
          <div className={`w-11 h-11 rounded-xl ${colors.bg} ${colors.border} border-2
                           flex items-center justify-center flex-shrink-0`}>
            <Ic n={meta.icon} s={20} c={colors.icon} />
          </div>
          <div className="flex-1 min-w-0">
            <div className={`text-sm font-extrabold ${colors.text} uppercase tracking-wide`}>
              Authorization Required
            </div>
            <div className="text-base font-bold text-white mt-0.5">{meta.label}</div>
          </div>
          {step === "prompt" && (
            <button onClick={onCancel}
              className="text-muted hover:text-white transition-colors flex-shrink-0">
              <Ic n="close" s={16} />
            </button>
          )}
        </div>

        <div className="p-6">

          {/* ── Prompt state ── */}
          {step === "prompt" && (
            <div className="flex flex-col gap-5">
              <div className="bg-elevated rounded-xl p-4 border border-border">
                <div className="text-[10px] font-bold text-muted uppercase tracking-wider mb-2">
                  Action Summary
                </div>
                <div className="text-sm text-ink leading-relaxed">{summary}</div>
              </div>

              <div className="flex items-start gap-3 text-xs text-muted leading-relaxed">
                <Ic n="shield" s={14} c="#6B7280" />
                <span>
                  This action requires your cryptographic signature and will be
                  permanently recorded in the audit log.
                </span>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={onCancel}
                  className="btn btn-md flex-1 justify-center border border-white/8
                             bg-white/3 text-sub hover:bg-white/6 rounded-xl text-sm font-semibold">
                  Cancel
                </button>
                <button
                  onClick={handleAuthorize}
                  className="btn btn-primary btn-md flex-1 justify-center gap-2">
                  <Ic n="shield" s={15} c="#fff" />
                  Authorize
                </button>
              </div>
            </div>
          )}

          {/* ── Signing state ── */}
          {step === "signing" && (
            <div className="flex flex-col items-center gap-4 py-6">
              <div className="relative">
                <div className={`w-16 h-16 rounded-2xl ${colors.bg} ${colors.border} border-2
                                 flex items-center justify-center`}>
                  <Ic n="shield" s={28} c={colors.icon} />
                </div>
                <div className="absolute -bottom-1 -right-1 w-6 h-6 bg-card rounded-full
                                flex items-center justify-center border border-border">
                  <Spinner s={14} />
                </div>
              </div>
              <div className="text-center">
                <div className="text-sm font-bold text-ink">Signing with your key…</div>
                <div className="text-xs text-muted mt-1">This only takes a moment</div>
              </div>
            </div>
          )}

          {/* ── Done state ── */}
          {step === "done" && (
            <div className="flex flex-col items-center gap-4 py-6">
              <div className="w-16 h-16 rounded-2xl bg-green-500/10 border-2 border-green-500/30
                              flex items-center justify-center">
                <Ic n="check" s={28} c="#34D399" sw={3} />
              </div>
              <div className="text-center">
                <div className="text-sm font-bold text-white">Authorized</div>
                <div className="text-xs text-muted mt-1">Executing action…</div>
              </div>
            </div>
          )}

          {/* ── Error state ── */}
          {step === "error" && (
            <div className="flex flex-col gap-4">
              <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4
                              flex items-start gap-3">
                <Ic n="warning" s={16} c="#F87171" />
                <div className="text-sm text-danger">{error}</div>
              </div>
              <div className="flex gap-3">
                <button onClick={onCancel}
                  className="btn btn-surface btn-md flex-1 justify-center">
                  Cancel
                </button>
                <button onClick={() => { setStep("prompt"); setError(""); }}
                  className="btn btn-primary btn-md flex-1 justify-center gap-2">
                  <Ic n="refresh" s={14} c="#fff" /> Try Again
                </button>
              </div>
            </div>
          )}

          {/* ── No keypair state ── */}
          {step === "nokey" && (
            <div className="flex flex-col gap-4">
              <div className="bg-orange-500/8 border border-orange-500/25 rounded-xl p-5
                              flex flex-col items-center gap-3 text-center">
                <div className="w-12 h-12 rounded-xl bg-orange-500/10 border border-orange-500/20
                                flex items-center justify-center">
                  <Ic n="lock" s={22} c="#fb923c" />
                </div>
                <div>
                  <div className="text-sm font-bold text-orange-300">Signing Key Required</div>
                  <div className="text-xs text-muted mt-1.5 leading-relaxed">
                    You need to set up your cryptographic signing key before performing
                    sensitive actions. It only takes a moment.
                  </div>
                </div>
              </div>
              <div className="flex gap-3">
                <button onClick={onCancel}
                  className="btn btn-surface btn-md flex-1 justify-center">
                  Later
                </button>
                <button
                  onClick={() => {
                    onCancel();
                    // Dispatch event for App to navigate to settings
                    window.dispatchEvent(new CustomEvent("evoting:navigate", {
                      detail: { view: "settings", tab: "security" }
                    }));
                  }}
                  className="btn btn-md flex-1 justify-center gap-2
                             bg-orange-500/20 border border-orange-500/40 text-orange-300
                             hover:bg-orange-500/30 rounded-xl text-sm font-semibold">
                  <Ic n="settings" s={14} c="#fb923c" /> Set Up Key
                </button>
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}

/**
 * withStepUp — HOF that wraps an async action with step-up auth.
 * This is the primary way to use step-up auth in page components.
 *
 * Returns a { trigger, modal } object:
 *   trigger() — call this from onClick handlers
 *   modal     — render this in JSX (only visible when active)
 *
 * Example:
 *   const { trigger: importWithAuth, modal: authModal } = useStepUpAction(
 *     "IMPORT_CANDIDATES",
 *     `Import ${candidates.length} candidates`,
 *     async (headers) => importCandidatesJson(electionId, candidates, headers)
 *   );
 *
 *   return (
 *     <>
 *       <button onClick={importWithAuth}>Import</button>
 *       {authModal}
 *     </>
 *   );
 */
export function useStepUpAction(actionType, getSummary, action) {
  const [visible,  setVisible]  = useState(false);
  const [pending,  setPending]  = useState(false);
  const [summary,  setSummary]  = useState("");

  const trigger = useCallback(async () => {
    const s = typeof getSummary === "function" ? getSummary() : getSummary;
    setSummary(s);
    setVisible(true);
  }, [getSummary]);

  const handleAuthorize = useCallback(async (headers) => {
    setVisible(false);
    setPending(true);
    try {
      await action(headers);
    } finally {
      setPending(false);
    }
  }, [action]);

  const handleCancel = useCallback(() => {
    setVisible(false);
  }, []);

  const modal = visible ? (
    <StepUpModal
      actionType={actionType}
      summary={summary}
      onAuthorize={handleAuthorize}
      onCancel={handleCancel}
    />
  ) : null;

  return { trigger, modal, pending };
}
