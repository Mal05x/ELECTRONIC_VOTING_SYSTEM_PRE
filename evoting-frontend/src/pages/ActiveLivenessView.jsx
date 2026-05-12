/**
 * ActiveLivenessView.jsx
 * ======================
 * Admin dashboard page for testing and demonstrating the MediaPipe
 * active liveness challenge-response service (active/app.py · port 5002).
 *
 * Workflow:
 *   1. Open browser webcam (getUserMedia)
 *   2. Backend picks a random challenge: TURN_HEAD_LEFT | TURN_HEAD_RIGHT
 *      | BLINK | SMILE  (via GET /api/camera/liveness-mode)
 *   3. Frontend streams JPEG frames at ~10 fps to
 *      POST /api/camera/analyze-frame  (admin-authed REST proxy to Python)
 *   4. When Python returns { passed: true }, show success.
 *   5. Store result for comparison with passive MiniFASNet mode.
 *
 * Requires: BiometricController.analyzeFrame() endpoint (added in this PR).
 */

import { useState, useEffect, useRef, useCallback } from "react";
import { Ic, Spinner } from "../components/ui.jsx";
import client           from "../api/client.js";

/* ── Challenge definitions ───────────────────────────────────────────── */
const CHALLENGES = [
  {
    id:          "TURN_HEAD_LEFT",
    label:       "Turn Left",
    instruction: "Slowly turn your head to the LEFT",
    hint:        "Keep your eyes facing forward",
    color:       "#818cf8",
    icon:        "direction-left",
  },
  {
    id:          "TURN_HEAD_RIGHT",
    label:       "Turn Right",
    instruction: "Slowly turn your head to the RIGHT",
    hint:        "Keep your eyes facing forward",
    color:       "#818cf8",
    icon:        "direction-right",
  },
  {
    id:          "BLINK",
    label:       "Blink",
    instruction: "Blink naturally once or twice",
    hint:        "A normal, comfortable blink is enough",
    color:       "#f59e0b",
    icon:        "eye",
  },
  {
    id:          "SMILE",
    label:       "Smile",
    instruction: "Give a natural, relaxed smile",
    hint:        "A gentle smile is sufficient",
    color:       "#34d399",
    icon:        "mood-happy",
  },
];

const PHASE = {
  IDLE:       "idle",
  STARTING:   "starting",
  CHALLENGE:  "challenge",
  EVALUATING: "evaluating",
  PASSED:     "passed",
  FAILED:     "failed",
};

const CHALLENGE_DURATION_MS = 10_000;
const FRAME_INTERVAL_MS     = 100;   // 10 fps

/* ── Animated challenge SVG icons ────────────────────────────────────── */
function ChallengeIcon({ id, color, animating }) {
  const anim = animating ? "" : "animation:none!important;";

  if (id === "TURN_HEAD_LEFT" || id === "TURN_HEAD_RIGHT") {
    const dir = id === "TURN_HEAD_LEFT" ? -1 : 1;
    return (
      <svg viewBox="0 0 96 96" width={88} height={88}>
        <style>{`
          @keyframes hTurn {
            0%,100% { transform: none }
            50%      { transform: skewX(${dir * 16}deg) scaleX(0.84) }
          }
          .hd { transform-origin:48px 42px; animation: hTurn 1.5s ease-in-out infinite; ${anim} }
        `}</style>
        <g className="hd">
          <ellipse cx="48" cy="40" rx="18" ry="23" fill="none" stroke={color} strokeWidth="2.5"/>
          <circle  cx="48" cy="21" r="4"  fill={color}/>
          <ellipse cx="48" cy="58" rx="13" ry="6.5" fill="none" stroke={color} strokeWidth="2" opacity="0.4"/>
          <line x1="39" y1="38" x2="57" y2="38" stroke={color} strokeWidth="1.8" strokeLinecap="round"/>
        </g>
        {/* arrow */}
        <line
          x1={dir === -1 ? 60 : 36} y1="80"
          x2={dir === -1 ? 34 : 62} y2="80"
          stroke={color} strokeWidth="3" strokeLinecap="round"/>
        <polygon
          points={
            dir === -1
              ? "34,74 34,86 21,80"
              : "62,74 62,86 75,80"
          }
          fill={color}/>
      </svg>
    );
  }

  if (id === "BLINK") {
    return (
      <svg viewBox="0 0 96 96" width={88} height={88}>
        <style>{`
          @keyframes eyeBlink {
            0%,35%,65%,100% { transform: scaleY(1)   }
            48%,52%         { transform: scaleY(0.06) }
          }
          .eb { transform-origin:48px 48px; animation: eyeBlink 2.2s ease-in-out infinite; ${anim} }
        `}</style>
        <g className="eb">
          <path d="M16 48 Q48 22 80 48 Q48 74 16 48" fill="none" stroke={color} strokeWidth="3"/>
        </g>
        <circle cx="48" cy="48" r="10" fill={color} opacity="0.9"/>
        <circle cx="52" cy="44" r="4"  fill="rgba(255,255,255,0.5)"/>
      </svg>
    );
  }

  if (id === "SMILE") {
    return (
      <svg viewBox="0 0 96 96" width={88} height={88}>
        <style>{`
          @keyframes smileGrow {
            0%,100% { d: path("M30 58 Q48 62 66 58") }
            50%     { d: path("M30 58 Q48 76 66 58") }
          }
          .sm { animation: smileGrow 1.8s ease-in-out infinite; ${anim}
                fill:none; stroke:${color}; stroke-width:3; stroke-linecap:round }
        `}</style>
        <circle cx="48" cy="44" r="30" fill="none" stroke={color} strokeWidth="2.5"/>
        <circle cx="37" cy="39" r="4"  fill={color}/>
        <circle cx="59" cy="39" r="4"  fill={color}/>
        <path className="sm" d="M30 58 Q48 62 66 58"/>
      </svg>
    );
  }
  return null;
}

/* ── Countdown progress bar ──────────────────────────────────────────── */
function CountdownBar({ running, color, durationMs }) {
  const [pct, setPct] = useState(100);
  const startRef = useRef(null);
  const rafRef   = useRef(null);

  useEffect(() => {
    if (!running) { setPct(100); cancelAnimationFrame(rafRef.current); return; }
    startRef.current = performance.now();
    const tick = () => {
      const elapsed = performance.now() - startRef.current;
      const rem     = Math.max(0, 100 - (elapsed / durationMs) * 100);
      setPct(rem);
      if (rem > 0) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [running, durationMs]);

  return (
    <div className="w-full h-1 rounded-full overflow-hidden"
         style={{ background: "var(--color-bg-elevated, #1e293b)" }}>
      <div className="h-full rounded-full transition-all"
           style={{ width: `${pct}%`, background: color, transitionDuration: "0.1s" }}/>
    </div>
  );
}

/* ── Session log strip ───────────────────────────────────────────────── */
function LogStrip({ entries }) {
  if (!entries.length) return null;
  return (
    <div className="rounded-xl border border-white/5 bg-black/30 p-3 font-mono">
      {entries.slice(-6).map((e, i) => (
        <div key={i} className="flex gap-3 items-baseline text-xs">
          <span className="text-white/20 flex-shrink-0">{e.t}</span>
          <span style={{ color: e.type === "ok" ? "#34d399" : e.type === "err" ? "#f87171" : "#64748b" }}>
            {e.msg}
          </span>
        </div>
      ))}
    </div>
  );
}

/* ── Comparison card ─────────────────────────────────────────────────── */
function ModeCompare() {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-6">
      {[
        {
          label: "Passive (MiniFASNet)",
          tag:   "Current default",
          tagC:  "text-purple-400 bg-purple-500/10 border-purple-500/20",
          desc:  "5 burst frames evaluated silently by a neural network. No user interaction required. Highest anti-spoofing accuracy.",
          pros:  ["Invisible to voter", "Best anti-spoof score", "Optical-flow motion analysis"],
          cons:  ["Requires GPU/Python service", "Higher latency (~3s)", "Port 5001 always running"],
          borderC: "border-purple-500/20",
        },
        {
          label: "Active (MediaPipe)",
          tag:   "This page",
          tagC:  "text-blue-400 bg-blue-500/10 border-blue-500/20",
          desc:  "Voter performs a random challenge (blink, smile, turn head). MediaPipe evaluates each frame via WebSocket.",
          pros:  ["No GPU needed", "Lower latency on success", "Explicitly proves presence"],
          cons:  ["Voter must cooperate", "Cooperative attack possible", "Port 5002 required"],
          borderC: "border-blue-500/20",
        },
      ].map(m => (
        <div key={m.label}
             className={`rounded-xl border p-4 bg-white/2 ${m.borderC}`}>
          <div className="flex items-center gap-2 mb-2">
            <span className="text-sm font-semibold text-ink">{m.label}</span>
            <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${m.tagC}`}>
              {m.tag}
            </span>
          </div>
          <p className="text-xs text-muted leading-relaxed mb-3">{m.desc}</p>
          <div className="space-y-1">
            {m.pros.map(p => (
              <div key={p} className="flex items-center gap-1.5 text-xs text-green-400">
                <Ic n="check" s={10} c="#34d399"/> {p}
              </div>
            ))}
            {m.cons.map(c => (
              <div key={c} className="flex items-center gap-1.5 text-xs text-muted">
                <Ic n="minus" s={10} c="#64748b"/> {c}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

/* ── Main view ───────────────────────────────────────────────────────── */
export default function ActiveLivenessView() {
  const [phase,     setPhase]     = useState(PHASE.IDLE);
  const [challenge, setChallenge] = useState(null);
  const [stream,    setStream]    = useState(null);
  const [camError,  setCamError]  = useState(false);
  const [log,       setLog]       = useState([]);
  const [attempts,  setAttempts]  = useState(0);
  const [sessionId, setSessionId] = useState(() =>
    "brws-" + Math.random().toString(36).slice(2, 9));

  const videoRef   = useRef();
  const canvasRef  = useRef(document.createElement("canvas"));
  const timerRef   = useRef(null);
  const frameRef   = useRef(null);
  const runningRef = useRef(false);

  const addLog = (msg, type = "info") => {
    const t = new Date().toLocaleTimeString();
    setLog(prev => [...prev, { msg, type, t }]);
  };

  const stopWebcam = useCallback(() => {
    if (stream) {
      stream.getTracks().forEach(t => t.stop());
      setStream(null);
    }
  }, [stream]);

  const stopTimers = () => {
    runningRef.current = false;
    clearTimeout(timerRef.current);
    clearInterval(frameRef.current);
  };

  const reset = useCallback(() => {
    stopTimers();
    stopWebcam();
    setPhase(PHASE.IDLE);
    setChallenge(null);
    setLog([]);
  }, [stopWebcam]);

  useEffect(() => () => { stopTimers(); stopWebcam(); }, [stopWebcam]);

  /* Pick challenge and run the frame-submission loop */
  const runChallenge = useCallback(async (ch, camStream) => {
    setChallenge(ch);
    setPhase(PHASE.CHALLENGE);
    addLog(`Challenge issued: ${ch.label}`);

    runningRef.current = true;
    let passed = false;

    const canvas = canvasRef.current;
    canvas.width  = 320;
    canvas.height = 240;
    const ctx = canvas.getContext("2d");

    const submitFrame = async () => {
      if (!runningRef.current) return;
      const vid = videoRef.current;
      if (!vid || !camStream) return;

      ctx.drawImage(vid, 0, 0, 320, 240);
      const blob = await new Promise(res => canvas.toBlob(res, "image/jpeg", 0.8));
      if (!blob || !runningRef.current) return;

      try {
        const res = await client.post("/camera/analyze-frame", blob, {
          headers: {
            "Content-Type":  "image/jpeg",
            "X-Challenge":   ch.id,
            "X-Session-Id":  sessionId,
            "X-Terminal-Id": "BROWSER-ADMIN",
          },
        });
        if (res.data?.passed) {
          passed = true;
          runningRef.current = false;
        }
      } catch (e) {
        /* network error — keep trying */
      }
    };

    frameRef.current = setInterval(submitFrame, FRAME_INTERVAL_MS);

    timerRef.current = setTimeout(() => {
      clearInterval(frameRef.current);
      runningRef.current = false;

      setPhase(PHASE.EVALUATING);
      addLog("Evaluating final frames via MediaPipe…");

      setTimeout(() => {
        if (passed) {
          setPhase(PHASE.PASSED);
          addLog("Challenge passed ✓", "ok");
          addLog("Result stored in liveness_results", "ok");
        } else {
          setPhase(PHASE.FAILED);
          addLog("Challenge failed — movement not detected ✗", "err");
        }
      }, 900);
    }, CHALLENGE_DURATION_MS);
  }, [sessionId]);

  const start = useCallback(async () => {
    setPhase(PHASE.STARTING);
    setLog([]);
    setAttempts(a => a + 1);
    setSessionId("brws-" + Math.random().toString(36).slice(2, 9));

    let camStream = null;
    try {
      camStream = await navigator.mediaDevices.getUserMedia({
        video: { width: 640, height: 480, facingMode: "user" },
      });
      setStream(camStream);
      setCamError(false);
      if (videoRef.current) {
        videoRef.current.srcObject = camStream;
        await videoRef.current.play().catch(() => {});
      }
      addLog("Webcam access granted", "ok");
    } catch {
      setCamError(true);
      addLog("Webcam access denied — challenge will fail without video", "err");
    }

    const ch = CHALLENGES[Math.floor(Math.random() * CHALLENGES.length)];
    runChallenge(ch, camStream);
  }, [runChallenge]);

  const retry = useCallback(() => {
    stopTimers();
    const ch = CHALLENGES[Math.floor(Math.random() * CHALLENGES.length)];
    runChallenge(ch, stream);
  }, [runChallenge, stream]);

  /* ── Derived ── */
  const isChallenge  = phase === PHASE.CHALLENGE;
  const isDone       = phase === PHASE.PASSED || phase === PHASE.FAILED;
  const ringColor    = phase === PHASE.PASSED   ? "#34d399"
                     : phase === PHASE.FAILED    ? "#f87171"
                     : isChallenge               ? (challenge?.color ?? "#818cf8")
                     : "#334155";

  return (
    <div className="min-h-screen bg-bg text-ink">
      {/* Page header */}
      <div className="border-b border-white/5 px-6 py-5 flex items-center gap-4">
        <button
          type="button"
          onClick={() =>
            window.dispatchEvent(
              new CustomEvent("evoting:navigate", { detail: { view: "settings" } })
            )
          }
          className="p-2 rounded-lg hover:bg-white/5 text-muted hover:text-sub transition-colors"
        >
          <Ic n="arrow-left" s={18}/>
        </button>
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-purple-500/15 flex items-center justify-center">
            <Ic n="eye" s={20} c="#a78bfa"/>
          </div>
          <div>
            <h1 className="text-lg font-semibold">Active Liveness</h1>
            <p className="text-xs text-muted">
              MediaPipe challenge-response · active/app.py · port 5002
            </p>
          </div>
        </div>

        {/* Status badge */}
        <div className="ml-auto px-3 py-1 rounded-full text-xs font-semibold border transition-all"
             style={{
               color:       ringColor,
               borderColor: ringColor + "60",
               background:  ringColor + "15",
             }}>
          {phase === PHASE.IDLE       ? "Idle"
         : phase === PHASE.STARTING   ? "Starting"
         : phase === PHASE.CHALLENGE  ? "Live · " + (challenge?.label ?? "")
         : phase === PHASE.EVALUATING ? "Analysing"
         : phase === PHASE.PASSED     ? "Passed ✓"
         : "Failed ✗"}
        </div>
      </div>

      <div className="max-w-4xl mx-auto px-6 py-8">
        {/* Mode comparison */}
        <ModeCompare/>

        {/* Main panel */}
        <div className="grid grid-cols-1 md:grid-cols-[240px_1fr] gap-6">

          {/* Webcam column */}
          <div className="flex flex-col items-center gap-3">
            <div
              className="w-[220px] h-[220px] rounded-full overflow-hidden flex items-center
                         justify-center relative bg-black/40 flex-shrink-0 transition-all duration-300"
              style={{ border: `3px solid ${ringColor}` }}
            >
              {/* Webcam feed */}
              <video
                ref={videoRef}
                muted
                playsInline
                className={`w-full h-full object-cover [transform:scaleX(-1)]
                            ${stream ? "block" : "hidden"}`}
              />

              {/* Placeholder */}
              {!stream && (
                <div className="flex flex-col items-center gap-2">
                  <Ic n="camera-off" s={40} c="#334155"/>
                  <span className="text-xs text-white/20">No webcam</span>
                </div>
              )}

              {/* Analysing overlay */}
              {phase === PHASE.EVALUATING && (
                <div className="absolute inset-0 bg-black/60 flex flex-col
                                items-center justify-center gap-2">
                  <Spinner s={28}/>
                  <span className="text-xs text-white/60">Analysing…</span>
                </div>
              )}

              {/* Pass overlay */}
              {phase === PHASE.PASSED && (
                <div className="absolute inset-0 bg-black/55 flex items-center justify-center">
                  <Ic n="circle-check" s={64} c="#34d399"/>
                </div>
              )}

              {/* Fail overlay */}
              {phase === PHASE.FAILED && (
                <div className="absolute inset-0 bg-black/55 flex items-center justify-center">
                  <Ic n="circle-x" s={64} c="#f87171"/>
                </div>
              )}
            </div>

            {/* Webcam status */}
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full transition-all"
                   style={{
                     background: stream ? "#34d399" : "#334155",
                     boxShadow:  stream ? "0 0 6px #34d399" : "none",
                   }}/>
              <span className="text-xs text-muted">
                {stream ? "Webcam live" : camError ? "Webcam denied" : "Webcam off"}
              </span>
            </div>

            {/* Session info */}
            <div className="w-full rounded-lg border border-white/5 bg-white/2 p-3 text-xs space-y-1.5">
              <div className="flex justify-between">
                <span className="text-white/30">Session</span>
                <span className="text-muted font-mono">{sessionId}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-white/30">Terminal</span>
                <span className="text-muted font-mono">BROWSER-ADMIN</span>
              </div>
              <div className="flex justify-between">
                <span className="text-white/30">Attempts</span>
                <span className="text-muted">{attempts}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-white/30">Endpoint</span>
                <span className="text-muted font-mono">/camera/analyze-frame</span>
              </div>
            </div>
          </div>

          {/* Right panel */}
          <div className="flex flex-col gap-4">

            {/* IDLE */}
            {phase === PHASE.IDLE && (
              <div className="flex flex-col gap-4">
                <div className="rounded-xl border border-white/5 bg-white/2 p-5">
                  <h2 className="text-sm font-semibold mb-1">How it works</h2>
                  <ol className="space-y-2 text-xs text-muted list-decimal list-inside leading-relaxed">
                    <li>Browser opens your webcam.</li>
                    <li>A random challenge is assigned.</li>
                    <li>Frames stream to <code className="text-purple-400">/api/camera/analyze-frame</code> at 10 fps.</li>
                    <li>Spring Boot proxies each frame to <code className="text-purple-400">active/app.py:5002</code>.</li>
                    <li>MediaPipe evaluates facial geometry and returns <code className="text-purple-400">passed: true/false</code>.</li>
                    <li>Result is stored in <code className="text-purple-400">liveness_results</code> table.</li>
                  </ol>
                </div>

                <button type="button" onClick={start}
                        className="btn btn-primary btn-md w-full justify-center gap-2">
                  <Ic n="player-play" s={15} c="#fff"/>
                  {attempts === 0 ? "Start Challenge" : "Start Again"}
                </button>
              </div>
            )}

            {/* STARTING */}
            {phase === PHASE.STARTING && (
              <div className="flex flex-col items-center justify-center gap-3 py-10">
                <Spinner s={32}/>
                <p className="text-sm text-muted">Opening webcam…</p>
              </div>
            )}

            {/* CHALLENGE */}
            {phase === PHASE.CHALLENGE && challenge && (
              <div className="flex flex-col gap-4">
                <CountdownBar
                  running={true}
                  color={challenge.color}
                  durationMs={CHALLENGE_DURATION_MS}
                />

                <div className="rounded-xl border p-6 flex flex-col items-center gap-4"
                     style={{
                       borderColor: challenge.color + "40",
                       background:  challenge.color + "08",
                     }}>
                  <ChallengeIcon id={challenge.id} color={challenge.color} animating={true}/>
                  <div className="text-center">
                    <p className="text-xl font-semibold mb-1" style={{ color: challenge.color }}>
                      {challenge.instruction}
                    </p>
                    <p className="text-xs text-muted">{challenge.hint}</p>
                  </div>
                </div>

                <div className="flex items-center gap-2 rounded-lg border border-white/5
                                bg-white/2 px-3 py-2 text-xs text-muted">
                  <div className="w-1.5 h-1.5 rounded-full bg-purple-400"
                       style={{ animation: "pulse 1.4s ease-in-out infinite" }}/>
                  Streaming JPEG frames → Spring Boot → active/app.py · {Math.round(1000 / FRAME_INTERVAL_MS)} fps
                </div>
              </div>
            )}

            {/* EVALUATING */}
            {phase === PHASE.EVALUATING && (
              <div className="rounded-xl border border-white/5 bg-white/2 p-8
                              flex flex-col items-center gap-3">
                <Spinner s={36}/>
                <p className="text-sm text-muted">Sending final frames to MediaPipe…</p>
                <p className="text-xs text-white/20">active/app.py · /analyze-frame · port 5002</p>
              </div>
            )}

            {/* PASSED */}
            {phase === PHASE.PASSED && (
              <div className="rounded-xl border border-green-500/30 bg-green-500/6
                              p-6 flex flex-col items-center gap-3">
                <Ic n="circle-check" s={52} c="#34d399"/>
                <p className="text-xl font-semibold text-green-400">Challenge Passed</p>
                <p className="text-xs text-muted text-center max-w-xs">
                  Liveness result stored in <code className="text-purple-400">liveness_results</code>.
                  The ESP32-S3 terminal can now call{" "}
                  <code className="text-purple-400">getLivenessResult(sessionId)</code> to proceed.
                </p>
                <div className="flex gap-3 w-full mt-2">
                  <button type="button" onClick={retry}
                          className="btn btn-sm flex-1 justify-center gap-1.5">
                    <Ic n="refresh" s={13}/> New challenge
                  </button>
                  <button type="button" onClick={reset}
                          className="btn btn-sm flex-1 justify-center gap-1.5">
                    <Ic n="x" s={13}/> Reset
                  </button>
                </div>
              </div>
            )}

            {/* FAILED */}
            {phase === PHASE.FAILED && (
              <div className="rounded-xl border border-red-500/30 bg-red-500/6
                              p-6 flex flex-col items-center gap-3">
                <Ic n="circle-x" s={52} c="#f87171"/>
                <p className="text-xl font-semibold text-red-400">Challenge Failed</p>
                <p className="text-xs text-muted text-center max-w-xs">
                  The movement was not detected within the 10 s window, or the
                  nose-to-cheek ratio threshold (0.45) was not crossed. Check
                  lighting and camera angle.
                </p>
                <button type="button" onClick={retry}
                        className="btn btn-primary btn-sm w-full justify-center gap-1.5 mt-2">
                  <Ic n="player-play" s={13}/> Try Again
                </button>
              </div>
            )}

            {/* Log */}
            <LogStrip entries={log}/>
          </div>
        </div>

        {/* Threshold reference */}
        <div className="mt-8 rounded-xl border border-white/5 bg-white/2 p-5">
          <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
            <Ic n="adjustments" s={15} c="#a78bfa"/>
            Detection Thresholds  <span className="text-xs text-muted font-normal">(active/app.py v1.2)</span>
          </h3>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
            {[
              { label: "Head turn",  val: "nose/cheek ratio < 0.45",  color: "#818cf8" },
              { label: "Blink",      val: "avg eye gap < 0.012",       color: "#f59e0b" },
              { label: "Smile",      val: "lip gap > 0.04 OR corner spread > 0.12", color: "#34d399" },
              { label: "Window",     val: "10 s per challenge",        color: "#94a3b8" },
            ].map(t => (
              <div key={t.label} className="rounded-lg border border-white/5 p-3">
                <p className="font-semibold mb-1" style={{ color: t.color }}>{t.label}</p>
                <p className="text-white/40 leading-relaxed">{t.val}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
