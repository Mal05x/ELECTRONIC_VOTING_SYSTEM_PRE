/**
 * BiometricCaptureView.jsx
 * ========================
 * Admin-facing page showing:
 *   Tab 1 — Capture Guide: voter instructions + animated face overlay
 *   Tab 2 — Live Debug Preview: upload a JPEG and see what the detector sees
 *   Tab 3 — Registration Flow: step-by-step walkthrough of enrollment
 *   Tab 4 — Model Status: which liveness model is active, health check
 */

import { useState, useRef, useEffect, useCallback } from "react";
import { Ic, SectionHeader, Spinner } from "../components/ui.jsx";
import client from "../api/client.js";

// ─── Palette ─────────────────────────────────────────────────────────────────
const P = {
  purple:  "#8B5CF6",
  purpleL: "#A78BFA",
  purpleD: "rgba(139,92,246,0.12)",
  success: "#34D399",
  warning: "#FCD34D",
  danger:  "#F87171",
  ink:     "#F0ECFF",
  sub:     "#8B7FA8",
  border:  "rgba(139,92,246,0.18)",
  surface: "#0D0D1A",
  card:    "#111122",
};

// ─── Animated face capture preview component ─────────────────────────────────
function FaceCapturePreview({ step }) {
  const canvasRef = useRef(null);
  const animRef   = useRef(null);
  const tickRef   = useRef(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");

    const draw = () => {
      const W = canvas.width, H = canvas.height;
      const t = tickRef.current++;
      ctx.clearRect(0, 0, W, H);

      // Background
      ctx.fillStyle = "#07070E";
      ctx.fillRect(0, 0, W, H);

      const cx = W / 2, cy = H / 2 - 10;
      const ovalW = 120, ovalH = 150;

      // ── Grid lines (subtle) ──────────────────────────────────────────
      ctx.strokeStyle = "rgba(139,92,246,0.06)";
      ctx.lineWidth = 1;
      for (let x = 0; x < W; x += 24) {
        ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke();
      }
      for (let y = 0; y < H; y += 24) {
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke();
      }

      const pulse = 0.5 + 0.5 * Math.sin(t * 0.06);
      const alpha = step === 1 ? 0.4 + 0.4 * pulse
                  : step === 2 ? 1.0
                  : step >= 3  ? 0.3 : 0.6;

      // ── Face oval guide ───────────────────────────────────────────────
      const guideColor = step >= 2 ? P.success : P.purple;
      ctx.strokeStyle = guideColor;
      ctx.lineWidth   = step === 1 ? 2 : 2.5;
      ctx.globalAlpha = alpha;
      ctx.beginPath();
      ctx.ellipse(cx, cy, ovalW / 2, ovalH / 2, 0, 0, Math.PI * 2);
      ctx.stroke();

      // Glow
      if (step === 2) {
        ctx.shadowBlur   = 18;
        ctx.shadowColor  = P.success;
        ctx.beginPath();
        ctx.ellipse(cx, cy, ovalW / 2, ovalH / 2, 0, 0, Math.PI * 2);
        ctx.stroke();
        ctx.shadowBlur = 0;
      }

      // ── Corner brackets ────────────────────────────────────────────────
      const bx = cx - ovalW / 2 - 6, by = cy - ovalH / 2 - 6;
      const bW  = ovalW + 12, bH = ovalH + 12;
      ctx.lineWidth = 2.5;
      ctx.globalAlpha = 1;
      const brackets = [
        [bx,    by,    1, 1],  [bx+bW, by,    -1, 1],
        [bx,    by+bH, 1,-1],  [bx+bW, by+bH, -1,-1],
      ];
      brackets.forEach(([x, y, dx, dy]) => {
        ctx.beginPath();
        ctx.moveTo(x + dx * 18, y);
        ctx.lineTo(x, y);
        ctx.lineTo(x, y + dy * 18);
        ctx.stroke();
      });

      // ── Scan line (steps 2-3 only) ─────────────────────────────────────
      if (step >= 2 && step <= 3) {
        const scanY = by + ((t * 2.5) % (bH + 20)) - 10;
        const grad = ctx.createLinearGradient(bx, scanY - 8, bx, scanY + 8);
        grad.addColorStop(0,   "rgba(139,92,246,0)");
        grad.addColorStop(0.5, "rgba(139,92,246,0.7)");
        grad.addColorStop(1,   "rgba(139,92,246,0)");
        ctx.strokeStyle = grad;
        ctx.globalAlpha = 0.9;
        ctx.lineWidth   = 2;
        ctx.beginPath();
        ctx.moveTo(bx, scanY);
        ctx.lineTo(bx + bW, scanY);
        ctx.stroke();
      }

      // ── Burst frame indicators (step 3) ───────────────────────────────
      if (step === 3) {
        const framesDone = Math.floor((t % 80) / 16);
        for (let i = 0; i < 5; i++) {
          const fx = cx - 60 + i * 30, fy = cy + ovalH / 2 + 20;
          ctx.globalAlpha = 1;
          ctx.fillStyle   = i < framesDone ? P.success : P.purpleD;
          ctx.strokeStyle = i < framesDone ? P.success : P.border;
          ctx.lineWidth   = 1.5;
          ctx.beginPath();
          ctx.roundRect(fx - 9, fy - 6, 18, 12, 3);
          ctx.fill();
          ctx.stroke();
        }
      }

      // ── Check mark (step 4) ────────────────────────────────────────────
      if (step === 4) {
        ctx.globalAlpha = 1;
        ctx.strokeStyle = P.success;
        ctx.lineWidth   = 4;
        ctx.lineCap     = "round";
        ctx.beginPath();
        ctx.moveTo(cx - 24, cy);
        ctx.lineTo(cx - 6,  cy + 18);
        ctx.lineTo(cx + 28, cy - 22);
        ctx.stroke();
      }

      // ── Status text ────────────────────────────────────────────────────
      ctx.globalAlpha = 1;
      const labels = ["", "Position face", "Face detected!", "Scanning…", "Verified ✓"];
      if (labels[step]) {
        const col = step === 4 ? P.success
                  : step === 2 ? P.success
                  : P.sub;
        ctx.fillStyle   = col;
        ctx.font        = "bold 13px 'Plus Jakarta Sans', sans-serif";
        ctx.textAlign   = "center";
        ctx.fillText(labels[step], cx, H - 12);
      }

      animRef.current = requestAnimationFrame(draw);
    };

    animRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animRef.current);
  }, [step]);

  return (
    <canvas
      ref={canvasRef}
      width={280} height={300}
      style={{ borderRadius: 14, border: `1px solid ${P.border}`, display: "block" }}
    />
  );
}

// ─── Tab: Capture Guide ───────────────────────────────────────────────────────
function CaptureGuideTab() {
  const [step, setStep] = useState(1);

  const STEPS = [
    {
      n: 1, icon: "chip", title: "Card & Authentication",
      desc: "Voter taps JCOP4 smart card on PN5180 NFC reader. The card is detected, the MFA voting applet is selected via AID, and a secure channel is established. PIN entry and fingerprint scan follow.",
      tips: ["Hold card flat — no tilt", "Wait for the beep", "Do not remove until confirmed"],
      color: P.purple,
    },
    {
      n: 2, icon: "voters", title: "Position Face",
      desc: "The TFT display shows a face outline guide. The voter should stand 30–50 cm from the ESP32-CAM and look directly at the lens. The camera performs auto-exposure warm-up (3 discarded frames).",
      tips: ["Remove face coverings", "Look straight ahead", "Ensure good lighting", "Distance: 30–50 cm"],
      color: P.purple,
    },
    {
      n: 3, icon: "tally", title: "Burst Capture (5 frames)",
      desc: "The ESP32-S3 sends BURST:<sessionId> over UART to the ESP32-CAM. The camera captures 5 JPEG frames at 200ms intervals (~1 second total), assembles them as multipart form-data in PSRAM, and POSTs to the backend.",
      tips: ["Hold still for 1 second", "Keep eyes open", "Progress bar shown on display"],
      color: "#60A5FA",
    },
    {
      n: 4, icon: "shield", title: "Liveness Analysis",
      desc: "Spring Boot forwards frames to the Python liveness service. Two models run: MiniFASNetV2 (scale 2.7) and MiniFASNetV1SE (scale 4.0) on a face-detected crop. Optical flow checks for inter-frame motion. Fused score must exceed 0.72.",
      tips: ["Model: MiniFASNet multi-scale", "Flow weight: 35%", "Model weight: 65%", "Near-zero motion = rejected"],
      color: "#34D399",
    },
    {
      n: 5, icon: "vote", title: "Vote Cast",
      desc: "On liveness pass, the voter sees the candidate list on the TFT. They navigate with hardware buttons, confirm selection, complete a fingerprint re-scan, and the card signs the ballot with its non-extractable ECDSA P-256 key.",
      tips: ["Card signs vote on-chip", "SET_VOTED APDU prevents re-voting", "Receipt code generated", "Merkle root published after close"],
      color: P.warning,
    },
  ];

  return (
    <div className="flex gap-6">
      {/* Steps list */}
      <div className="flex flex-col gap-2 w-64 flex-shrink-0">
        {STEPS.map(s => (
          <button
            key={s.n}
            onClick={() => setStep(s.n)}
            className="flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all"
            style={{
              background: step === s.n ? `${s.color}18` : "transparent",
              border:     `1px solid ${step === s.n ? s.color : P.border}`,
            }}
          >
            <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0"
                 style={{ background: step === s.n ? s.color : P.surface }}>
              <span className="text-xs font-extrabold text-white">{s.n}</span>
            </div>
            <span className="text-xs font-semibold"
                  style={{ color: step === s.n ? P.ink : P.sub }}>
              {s.title}
            </span>
            {step === s.n && (
              <Ic n="arrow" s={12} c={s.color} />
            )}
          </button>
        ))}
      </div>

      {/* Detail + animation */}
      <div className="flex-1 flex gap-6 min-w-0">
        {/* Text */}
        <div className="flex-1 flex flex-col gap-4">
          <div>
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-xl flex items-center justify-center"
                   style={{ background: STEPS[step-1].color + "22" }}>
                <Ic n={STEPS[step-1].icon} s={16} c={STEPS[step-1].color} />
              </div>
              <span className="text-base font-extrabold" style={{ color: P.ink }}>
                Step {step} — {STEPS[step-1].title}
              </span>
            </div>
            <p className="text-sm leading-relaxed" style={{ color: P.sub }}>
              {STEPS[step-1].desc}
            </p>
          </div>

          {/* Tips */}
          <div className="rounded-xl p-4"
               style={{ background: P.surface, border: `1px solid ${P.border}` }}>
            <div className="text-xs font-bold mb-3" style={{ color: P.purpleL }}>
              Key Points
            </div>
            <div className="flex flex-col gap-2">
              {STEPS[step-1].tips.map((t, i) => (
                <div key={i} className="flex items-start gap-2">
                  <div className="w-4 h-4 rounded-full flex items-center justify-center mt-0.5 flex-shrink-0"
                       style={{ background: STEPS[step-1].color + "22" }}>
                    <div className="w-1.5 h-1.5 rounded-full"
                         style={{ background: STEPS[step-1].color }} />
                  </div>
                  <span className="text-xs" style={{ color: P.sub }}>{t}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Nav buttons */}
          <div className="flex gap-2 mt-auto">
            {step > 1 && (
              <button
                onClick={() => setStep(s => s - 1)}
                className="px-4 py-2 rounded-xl text-xs font-semibold transition-all"
                style={{ background: P.surface, border: `1px solid ${P.border}`, color: P.sub }}>
                ← Previous
              </button>
            )}
            {step < 5 && (
              <button
                onClick={() => setStep(s => s + 1)}
                className="px-4 py-2 rounded-xl text-xs font-semibold transition-all"
                style={{ background: `${P.purple}22`, border: `1px solid ${P.purple}`, color: P.purpleL }}>
                Next →
              </button>
            )}
          </div>
        </div>

        {/* Animation */}
        <div className="flex-shrink-0">
          <FaceCapturePreview step={step} />
        </div>
      </div>
    </div>
  );
}

// ─── Tab: Debug Preview ───────────────────────────────────────────────────────
function DebugPreviewTab() {
  const [imgFile,    setImgFile]    = useState(null);
  const [preview,    setPreview]    = useState(null);
  const [loading,    setLoading]    = useState(false);
  const [result,     setResult]     = useState(null);
  const [error,      setError]      = useState(null);
  const fileRef = useRef();

  const handleFile = e => {
    const f = e.target.files[0];
    if (!f) return;
    setImgFile(f);
    setPreview(URL.createObjectURL(f));
    setResult(null);
    setError(null);
  };

  const runPreview = async () => {
    if (!imgFile) return;
    setLoading(true); setError(null); setResult(null);
    try {
      const fd = new FormData();
      fd.append("frame", imgFile);
      const res = await client.post("/camera/debug-preview", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      setResult(res.data);
    } catch (e) {
      setError(e?.response?.data?.detail || e?.response?.data?.error || e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex flex-col gap-5">
      {/* Warning banner */}
      <div className="flex items-start gap-3 rounded-xl px-4 py-3"
           style={{ background: `${P.warning}10`, border: `1px solid ${P.warning}30` }}>
        <Ic n="warning" s={14} c={P.warning} />
        <p className="text-xs" style={{ color: P.warning }}>
          Debug endpoint is for development only. Disable in production
          by removing the <code>/debug-preview</code> route from the Python service.
          It returns face images over the wire.
        </p>
      </div>

      <div className="flex gap-6">
        {/* Upload */}
        <div className="flex flex-col gap-4 w-72 flex-shrink-0">
          <div
            onClick={() => fileRef.current.click()}
            className="rounded-xl border-2 border-dashed flex flex-col items-center
                       justify-center gap-2 cursor-pointer transition-all p-6"
            style={{ borderColor: P.border, background: P.surface, minHeight: 160 }}>
            {preview ? (
              <img src={preview} alt="preview"
                   className="max-h-36 rounded-lg object-contain" />
            ) : (
              <>
                <Ic n="voters" s={28} c={P.sub} />
                <span className="text-xs text-center" style={{ color: P.sub }}>
                  Click to upload a JPEG frame<br/>
                  (same as what ESP32-CAM sends)
                </span>
              </>
            )}
          </div>
          <input ref={fileRef} type="file" accept="image/jpeg,image/jpg"
                 className="hidden" onChange={handleFile} />

          <button
            onClick={runPreview}
            disabled={!imgFile || loading}
            className="px-4 py-2.5 rounded-xl text-sm font-bold transition-all
                       disabled:opacity-40 disabled:cursor-not-allowed"
            style={{ background: `${P.purple}22`, border: `1px solid ${P.purple}`, color: P.purpleL }}>
            {loading ? "Analysing…" : "Run Debug Preview"}
          </button>
        </div>

        {/* Result */}
        <div className="flex-1 flex flex-col gap-4">
          {error && (
            <div className="rounded-xl px-4 py-3"
                 style={{ background: `${P.danger}10`, border: `1px solid ${P.danger}30` }}>
              <p className="text-xs font-semibold" style={{ color: P.danger }}>Error: {error}</p>
              <p className="text-xs mt-1" style={{ color: P.sub }}>
                Make sure the Python liveness service is running and <code>LIVENESS_SECRET</code> is configured.
              </p>
            </div>
          )}

          {result && (
            <>
              {/* Annotated image */}
              {result.image_b64 && (
                <img
                  src={`data:image/jpeg;base64,${result.image_b64}`}
                  alt="annotated"
                  className="rounded-xl max-w-full border"
                  style={{ borderColor: P.border }}
                />
              )}

              {/* Metrics */}
              <div className="grid grid-cols-2 gap-3">
                {[
                  { label: "Face Detected",  val: result.face_detected ? "Yes" : "No",
                    color: result.face_detected ? P.success : P.danger },
                  { label: "Face Score",     val: result.face_score?.toFixed(3) ?? "—",  color: P.purpleL },
                  { label: "Liveness Passed",val: result.livenessPassed ? "PASS" : "FAIL",
                    color: result.livenessPassed ? P.success : P.danger },
                  { label: "Confidence",     val: result.confidenceScore?.toFixed(3) ?? "—", color: P.purpleL },
                  { label: "Model",          val: result.model ?? "—",  color: P.sub },
                  { label: "Detail",         val: result.detail ?? "—", color: P.sub },
                ].map(({ label, val, color }) => (
                  <div key={label} className="rounded-xl p-3"
                       style={{ background: P.surface, border: `1px solid ${P.border}` }}>
                    <div className="text-[10px] mb-1" style={{ color: P.sub }}>{label}</div>
                    <div className="text-sm font-bold" style={{ color }}>{val}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {!result && !error && !loading && (
            <div className="flex flex-col items-center justify-center gap-2 flex-1"
                 style={{ minHeight: 200 }}>
              <Ic n="shield" s={32} c={P.border} />
              <span className="text-xs" style={{ color: P.sub }}>
                Upload a JPEG to see face detection and liveness analysis
              </span>
            </div>
          )}

          {loading && (
            <div className="flex items-center justify-center gap-3 flex-1"
                 style={{ minHeight: 200 }}>
              <Spinner size={20} />
              <span className="text-xs" style={{ color: P.sub }}>Running inference…</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Tab: Registration Flow ───────────────────────────────────────────────────
function RegistrationFlowTab() {
  const [active, setActive] = useState(0);

  const STAGES = [
    {
      icon: "chip",
      title: "Terminal Initiates",
      color: P.purple,
      what: "The voter presents their JCOP4 card at the terminal for the first time. The card is detected by the PN5180, the voting applet is selected, and the card's public key is read.",
      system: "ESP32-S3 → POST /api/terminal/pending-registration\nPayload: { terminalId, pollingUnitId, cardIdHash, voterPublicKey }",
      display: "TFT shows: 'New Card Detected — Registration pending'",
      important: "The card must be physically present. No demographics are committed yet.",
    },
    {
      icon: "voters",
      title: "Admin Dashboard Review",
      color: "#60A5FA",
      what: "The pending registration appears in the Registration tab of the admin dashboard. An INEC official reviews the voter's physical documents and fills in the demographics form.",
      system: "Dashboard → GET /api/registration/pending\nDisplays: cardIdHash, pollingUnit, timestamp",
      display: "Admin dashboard shows the pending card with a form to fill demographics.",
      important: "Demographics are encrypted with pgcrypto before storage. Only authorised decryption is audit-logged.",
    },
    {
      icon: "enroll",
      title: "Face Enrollment",
      color: P.purpleL,
      what: "The terminal operator triggers face enrollment via the admin dashboard Enrollment Queue. The voter looks at the ESP32-CAM which captures a grayscale frame and builds an 8×8 luminance grid embedding.",
      system: "ESP32-CAM UART ← ENROLL\nReturns: EMBED:<64-byte hex>",
      display: "TFT: 'Enrollment — Look at camera'",
      important: "⚠ The 8×8 luminance grid is a lighting fingerprint, not face recognition. It is stored on the card but not currently verified during auth. This is a known gap — ArcFace upgrade planned.",
    },
    {
      icon: "lock",
      title: "Commit & Activate",
      color: P.success,
      what: "The admin clicks 'Commit Registration'. The backend stores the demographics, links the card public key to the voter record, and marks the voter as eligible for the assigned polling unit.",
      system: "Dashboard → POST /api/registration/commit\nRequired: step-up authentication (ECDSA signature)",
      display: "Dashboard shows voter as REGISTERED with green status badge.",
      important: "Geographic eligibility — the voter can only vote at their registered polling unit terminal.",
    },
    {
      icon: "vote",
      title: "Ready to Vote",
      color: P.warning,
      what: "On election day, the voter approaches the terminal. The full 5-factor auth flow runs: Card → PIN → Fingerprint → Liveness → Server auth. Only then is the vote screen shown.",
      system: "Full auth chain:\n1. NFC card detection\n2. ECDSA challenge-response\n3. PIN try-counter\n4. R307 fingerprint match\n5. Burst liveness\n6. JWT + server validation",
      display: "TFT step-dot progress: ● ● ● ● ○ (steps 1-4 done, vote pending)",
      important: "The card's voted flag (SET_VOTED APDU) prevents double-voting at hardware level regardless of backend state.",
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      {/* Timeline */}
      <div className="flex items-center gap-0 overflow-x-auto pb-2">
        {STAGES.map((s, i) => (
          <div key={i} className="flex items-center flex-shrink-0">
            <button
              onClick={() => setActive(i)}
              className="flex flex-col items-center gap-1.5 px-3 transition-all"
              style={{ minWidth: 80 }}>
              <div className="w-10 h-10 rounded-xl flex items-center justify-center transition-all"
                   style={{
                     background: active === i ? `${s.color}22` : P.surface,
                     border:     `2px solid ${active === i ? s.color : P.border}`,
                     boxShadow:  active === i ? `0 0 12px ${s.color}44` : "none",
                   }}>
                <Ic n={s.icon} s={16} c={active === i ? s.color : P.sub} />
              </div>
              <span className="text-[9px] text-center font-semibold leading-tight"
                    style={{ color: active === i ? s.color : P.sub, maxWidth: 64 }}>
                {s.title}
              </span>
            </button>
            {i < STAGES.length - 1 && (
              <div className="w-6 h-0.5 flex-shrink-0"
                   style={{ background: i < active ? P.success : P.border }} />
            )}
          </div>
        ))}
      </div>

      {/* Detail card */}
      <div className="rounded-2xl p-5 flex flex-col gap-4"
           style={{ background: P.surface, border: `1px solid ${STAGES[active].color}40` }}>
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl flex items-center justify-center"
               style={{ background: `${STAGES[active].color}22` }}>
            <Ic n={STAGES[active].icon} s={17} c={STAGES[active].color} />
          </div>
          <div>
            <div className="text-sm font-extrabold" style={{ color: P.ink }}>
              Stage {active + 1} — {STAGES[active].title}
            </div>
          </div>
        </div>

        <p className="text-sm leading-relaxed" style={{ color: P.sub }}>
          {STAGES[active].what}
        </p>

        <div className="grid grid-cols-2 gap-3">
          {[
            { label: "System call", val: STAGES[active].system, mono: true },
            { label: "Display output", val: STAGES[active].display, mono: false },
          ].map(({ label, val, mono }) => (
            <div key={label} className="rounded-xl p-3"
                 style={{ background: P.card, border: `1px solid ${P.border}` }}>
              <div className="text-[10px] font-bold mb-2" style={{ color: P.purpleL }}>{label}</div>
              <pre className={`text-xs leading-relaxed whitespace-pre-wrap ${mono ? "font-mono" : ""}`}
                   style={{ color: P.sub }}>{val}</pre>
            </div>
          ))}
        </div>

        <div className="flex items-start gap-2 rounded-xl px-3 py-2.5"
             style={{ background: `${STAGES[active].color}0F`, border: `1px solid ${STAGES[active].color}30` }}>
          <Ic n="warning" s={13} c={STAGES[active].color} />
          <span className="text-xs" style={{ color: STAGES[active].color }}>
            {STAGES[active].important}
          </span>
        </div>
      </div>
    </div>
  );
}

// ─── Tab: Model Status ────────────────────────────────────────────────────────
function ModelStatusTab() {
  const [health,   setHealth]   = useState(null);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState(null);

  const fetchHealth = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const res = await client.get("/camera/liveness-health");
      setHealth(res.data);
    } catch (e) {
      setError(e?.response?.data?.error || e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchHealth(); }, [fetchHealth]);

  const MODELS = [
    {
      id: "minifasnet_multiscale",
      name: "MiniFASNet Multi-Scale",
      desc: "Runs MiniFASNetV2 (scale 2.7) + MiniFASNetV1SE (scale 4.0) on an Ultraface-detected face crop. Weighted average of real-class softmax scores.",
      files: ["MiniFASNetV2.onnx", "MiniFASNetV1SE.onnx", "version-RFB-320.onnx"],
      status: "active",
      accuracy: "Good",
      speed: "~60ms",
    },
    {
      id: "cdcn",
      name: "CDCN++ (Central Difference CNN)",
      desc: "Produces a depth map proxy; real faces have high-value maps, spoofs near zero. More accurate than MiniFASNet on printed photo attacks. Requires pre-trained weights from ZitongYu/CDCN.",
      files: ["CDCNpp.onnx", "version-RFB-320.onnx"],
      status: "pending_weights",
      accuracy: "Excellent",
      speed: "~90ms",
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      {/* Health card */}
      <div className="flex items-center justify-between rounded-xl px-4 py-3"
           style={{ background: P.surface, border: `1px solid ${P.border}` }}>
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center"
               style={{ background: health ? `${P.success}18` : `${P.danger}18` }}>
            <Ic n={health ? "check" : "warning"} s={15}
                c={health ? P.success : (error ? P.danger : P.sub)} />
          </div>
          <div>
            <div className="text-sm font-bold" style={{ color: P.ink }}>
              Liveness Service {health ? "Online" : (error ? "Unreachable" : "Checking…")}
            </div>
            {health && (
              <div className="text-xs" style={{ color: P.sub }}>
                Model: <span style={{ color: P.purpleL }}>{health.model}</span>
                &nbsp;·&nbsp;Burst: {health.burst_support ? "supported" : "single only"}
                &nbsp;·&nbsp;v{health.version}
              </div>
            )}
            {error && (
              <div className="text-xs" style={{ color: P.danger }}>{error}</div>
            )}
          </div>
        </div>
        <button onClick={fetchHealth}
                className="px-3 py-1.5 rounded-xl text-xs font-semibold transition-all"
                style={{ background: `${P.purple}18`, border: `1px solid ${P.border}`, color: P.purpleL }}>
          {loading ? "…" : "Refresh"}
        </button>
      </div>

      {/* Model cards */}
      <div className="flex flex-col gap-3">
        {MODELS.map(m => {
          const isActive = health?.model === m.id;
          const isPending = m.status === "pending_weights";
          return (
            <div key={m.id} className="rounded-xl p-4"
                 style={{
                   background: isActive ? `${P.purple}08` : P.surface,
                   border: `1px solid ${isActive ? P.purple : P.border}`,
                 }}>
              <div className="flex items-start justify-between gap-4 mb-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-extrabold" style={{ color: P.ink }}>{m.name}</span>
                  {isActive && (
                    <span className="text-[9px] px-2 py-0.5 rounded-full font-bold"
                          style={{ background: `${P.success}18`, color: P.success, border: `1px solid ${P.success}30` }}>
                      ACTIVE
                    </span>
                  )}
                  {isPending && (
                    <span className="text-[9px] px-2 py-0.5 rounded-full font-bold"
                          style={{ background: `${P.warning}18`, color: P.warning, border: `1px solid ${P.warning}30` }}>
                      NEEDS WEIGHTS
                    </span>
                  )}
                </div>
                <div className="flex gap-3 text-xs flex-shrink-0" style={{ color: P.sub }}>
                  <span>Accuracy: <b style={{ color: P.purpleL }}>{m.accuracy}</b></span>
                  <span>Speed: <b style={{ color: P.purpleL }}>{m.speed}</b></span>
                </div>
              </div>
              <p className="text-xs mb-3" style={{ color: P.sub }}>{m.desc}</p>
              <div className="flex flex-wrap gap-2">
                {m.files.map(f => (
                  <span key={f} className="text-[10px] px-2 py-1 rounded-lg font-mono"
                        style={{ background: P.card, color: P.purpleL, border: `1px solid ${P.border}` }}>
                    {f}
                  </span>
                ))}
              </div>
              {isPending && (
                <div className="mt-3 text-xs rounded-lg px-3 py-2"
                     style={{ background: `${P.warning}0C`, border: `1px solid ${P.warning}20`, color: P.warning }}>
                  To activate: email zitong.yu@oulu.fi for OULU-NPU Protocol 1 weights →
                  place at <code>liveness-service/models/CDCNpp_pretrained.pth</code> →
                  run <code>python cdcn_export.py</code> →
                  set <code>LIVENESS_MODEL=cdcn</code> in Render environment.
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Optical flow info */}
      <div className="rounded-xl p-4"
           style={{ background: P.surface, border: `1px solid ${P.border}` }}>
        <div className="flex items-center gap-2 mb-2">
          <Ic n="tally" s={14} c={P.purpleL} />
          <span className="text-sm font-bold" style={{ color: P.ink }}>Optical Flow Layer (always active)</span>
        </div>
        <p className="text-xs mb-3" style={{ color: P.sub }}>
          Applied on top of any model in burst mode. Farnebäck dense flow is computed across all 5 frames.
          A real face produces 0.08–2.0 px/frame of involuntary micro-movement. A static printed photo
          produces ≈0 motion and is rejected regardless of model score.
        </p>
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: "Flow weight",     val: "35%" },
            { label: "Model weight",    val: "65%" },
            { label: "Fused threshold", val: "0.72" },
            { label: "Min motion",      val: "0.08 px/f" },
            { label: "Max motion",      val: "8.0 px/f" },
            { label: "Frames",          val: "5 × 200ms" },
          ].map(({ label, val }) => (
            <div key={label} className="rounded-lg p-2.5"
                 style={{ background: P.card, border: `1px solid ${P.border}` }}>
              <div className="text-[9px] mb-1" style={{ color: P.sub }}>{label}</div>
              <div className="text-sm font-bold font-mono" style={{ color: P.purpleL }}>{val}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Main View ────────────────────────────────────────────────────────────────
const TABS = [
  { id: "guide",    label: "Capture Guide",      icon: "voters" },
  { id: "debug",    label: "Debug Preview",       icon: "shield" },
  { id: "flow",     label: "Registration Flow",   icon: "enroll" },
  { id: "model",    label: "Model Status",        icon: "chip"   },
];

export default function BiometricCaptureView() {
  const [tab, setTab] = useState("guide");

  return (
    <div className="p-6 flex flex-col gap-6 max-w-5xl mx-auto">
      <SectionHeader
        title="Biometric Capture"
        subtitle="ESP32-CAM liveness detection — capture guide, debug preview & model status"
        icon="voters"
      />

      {/* Tab bar */}
      <div className="flex gap-1 p-1 rounded-xl"
           style={{ background: P.surface, border: `1px solid ${P.border}` }}>
        {TABS.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className="flex-1 flex items-center justify-center gap-2 px-3 py-2.5
                       rounded-lg text-xs font-semibold transition-all"
            style={{
              background: tab === t.id ? `${P.purple}22` : "transparent",
              border:     `1px solid ${tab === t.id ? P.purple : "transparent"}`,
              color:       tab === t.id ? P.purpleL : P.sub,
            }}>
            <Ic n={t.icon} s={13} c={tab === t.id ? P.purpleL : P.sub} />
            {t.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="rounded-2xl p-5"
           style={{ background: P.card, border: `1px solid ${P.border}` }}>
        {tab === "guide" && <CaptureGuideTab />}
        {tab === "debug" && <DebugPreviewTab />}
        {tab === "flow"  && <RegistrationFlowTab />}
        {tab === "model" && <ModelStatusTab />}
      </div>
    </div>
  );
}
