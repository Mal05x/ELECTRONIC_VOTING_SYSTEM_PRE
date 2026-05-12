/**
 * ActiveLivenessView.jsx  v2.0
 * =============================
 * Fixed:
 *   BUG-1  Video element was hidden (display:none via Tailwind "hidden" class).
 *          Chromium + Firefox skip decoding frames for hidden video elements —
 *          ctx.drawImage() captured a blank image every time.
 *          Fix: video is always display:block; a positioned overlay provides
 *          the placeholder when no stream is active.
 *
 *   BUG-2  srcObject set before React re-render, so the video ref was stale
 *          in some cases.
 *          Fix: useEffect watches `stream` state and sets srcObject after
 *          every render cycle.
 *
 *   BUG-3  Frame loop started before video.readyState >= 2 (HAVE_CURRENT_DATA).
 *          Canvas drew blank frames until the browser had decoded at least one
 *          video frame.
 *          Fix: await canplay event before starting setInterval.
 *
 *   BUG-4  No explicit camera permission step — getUserMedia was called inside
 *          the same function that started the challenge, with no UI feedback
 *          while the browser permission dialog was showing.
 *          Fix: dedicated "Allow Camera" button with its own phase.
 */

import { useState, useEffect, useRef, useCallback } from "react";
import { Ic, Spinner }  from "../components/ui.jsx";
import client           from "../api/client.js";

/* ── Constants ───────────────────────────────────────────────────────── */
const CHALLENGES = [
  { id: "TURN_HEAD_LEFT",  label: "Turn Left",  instruction: "Slowly turn your head to the LEFT",  hint: "Keep eyes facing forward",      color: "#818cf8" },
  { id: "TURN_HEAD_RIGHT", label: "Turn Right", instruction: "Slowly turn your head to the RIGHT", hint: "Keep eyes facing forward",      color: "#818cf8" },
  { id: "BLINK",           label: "Blink",      instruction: "Blink naturally once or twice",       hint: "A normal blink is enough",     color: "#f59e0b" },
  { id: "SMILE",           label: "Smile",      instruction: "Give a natural, relaxed smile",       hint: "A gentle smile is sufficient", color: "#34d399" },
];

const PHASE = {
  IDLE:        "idle",
  CAM_REQUEST: "cam_request",
  CAM_READY:   "cam_ready",
  CHALLENGE:   "challenge",
  EVALUATING:  "evaluating",
  PASSED:      "passed",
  FAILED:      "failed",
};

const CHALLENGE_MS  = 10_000;
const FRAME_INTERVAL = 100;    // 10 fps

/* ── Animated challenge icons ────────────────────────────────────────── */
function ChallengeIcon({ id, color }) {
  if (id === "TURN_HEAD_LEFT" || id === "TURN_HEAD_RIGHT") {
    const d = id === "TURN_HEAD_LEFT" ? -1 : 1;
    return (
      <svg viewBox="0 0 96 96" width={88} height={88}>
        <style>{`
          @keyframes hT{0%,100%{transform:none}50%{transform:skewX(${d*16}deg) scaleX(.84)}}
          .hd{transform-origin:48px 42px;animation:hT 1.5s ease-in-out infinite}
        `}</style>
        <g className="hd">
          <ellipse cx="48" cy="40" rx="18" ry="23" fill="none" stroke={color} strokeWidth="2.5"/>
          <circle cx="48" cy="21" r="4" fill={color}/>
          <ellipse cx="48" cy="58" rx="13" ry="6.5" fill="none" stroke={color} strokeWidth="2" opacity=".4"/>
          <line x1="39" y1="38" x2="57" y2="38" stroke={color} strokeWidth="1.8" strokeLinecap="round"/>
        </g>
        <line x1={d===-1?60:36} y1="80" x2={d===-1?34:62} y2="80" stroke={color} strokeWidth="3" strokeLinecap="round"/>
        <polygon points={d===-1?"34,74 34,86 21,80":"62,74 62,86 75,80"} fill={color}/>
      </svg>
    );
  }
  if (id === "BLINK") return (
    <svg viewBox="0 0 96 96" width={88} height={88}>
      <style>{`@keyframes eb{0%,35%,65%,100%{transform:scaleY(1)}48%,52%{transform:scaleY(.06)}}.eb{transform-origin:48px 48px;animation:eb 2.2s ease-in-out infinite}`}</style>
      <g className="eb"><path d="M16 48 Q48 22 80 48 Q48 74 16 48" fill="none" stroke={color} strokeWidth="3"/></g>
      <circle cx="48" cy="48" r="10" fill={color} opacity=".9"/>
      <circle cx="52" cy="44" r="4"  fill="rgba(255,255,255,.5)"/>
    </svg>
  );
  if (id === "SMILE") return (
    <svg viewBox="0 0 96 96" width={88} height={88}>
      <style>{`@keyframes sg{0%,100%{d:path("M30 58 Q48 62 66 58")}50%{d:path("M30 58 Q48 76 66 58")}}.sm{animation:sg 1.8s ease-in-out infinite;fill:none;stroke:${color};stroke-width:3;stroke-linecap:round}`}</style>
      <circle cx="48" cy="44" r="30" fill="none" stroke={color} strokeWidth="2.5"/>
      <circle cx="37" cy="39" r="4" fill={color}/><circle cx="59" cy="39" r="4" fill={color}/>
      <path className="sm" d="M30 58 Q48 62 66 58"/>
    </svg>
  );
  return null;
}

/* ── Countdown bar ───────────────────────────────────────────────────── */
function CountdownBar({ running, color }) {
  const [pct, setPct] = useState(100);
  const startRef = useRef(null);
  const rafRef   = useRef(null);
  useEffect(() => {
    if (!running) { setPct(100); cancelAnimationFrame(rafRef.current); return; }
    startRef.current = performance.now();
    const tick = () => {
      const rem = Math.max(0, 100 - ((performance.now()-startRef.current)/CHALLENGE_MS)*100);
      setPct(rem);
      if (rem > 0) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [running]);

  return (
    <div className="w-full h-1.5 rounded-full overflow-hidden bg-white/5">
      <div className="h-full rounded-full" style={{ width:`${pct}%`, background:color, transition:"width .1s linear" }}/>
    </div>
  );
}

/* ── Log strip ───────────────────────────────────────────────────────── */
function Log({ entries }) {
  if (!entries.length) return null;
  return (
    <div className="rounded-xl border border-white/5 bg-black/30 px-3 py-2 font-mono">
      {entries.slice(-5).map((e,i) => (
        <div key={i} className="flex gap-2 text-xs">
          <span className="text-white/20 flex-shrink-0">{e.t}</span>
          <span style={{ color: e.type==="ok"?"#34d399":e.type==="err"?"#f87171":"#64748b" }}>{e.msg}</span>
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
  const [log,       setLog]       = useState([]);
  const [framesSent, setFramesSent] = useState(0);
  const [sessionId] = useState("brws-" + Math.random().toString(36).slice(2,9));

  const videoRef   = useRef(null);
  const canvasRef  = useRef(document.createElement("canvas"));
  const timerRef   = useRef(null);
  const intervalRef= useRef(null);
  const runRef     = useRef(false);
  const sentRef    = useRef(0);

  const addLog = (msg, type="info") =>
    setLog(p => [...p, { msg, type, t: new Date().toLocaleTimeString() }]);

  /* ── FIX BUG-2: set srcObject in effect, after React has rendered ── */
  useEffect(() => {
    const vid = videoRef.current;
    if (!vid) return;
    if (stream) {
      vid.srcObject = stream;
      // autoPlay attribute handles play; call play() as fallback
      vid.play().catch(e => addLog("Video play error: " + e.message, "err"));
    } else {
      vid.srcObject = null;
    }
  }, [stream]);

  const stopAll = useCallback(() => {
    runRef.current = false;
    clearTimeout(timerRef.current);
    clearInterval(intervalRef.current);
  }, []);

  const stopWebcam = useCallback(() => {
    stream?.getTracks().forEach(t => t.stop());
    setStream(null);
  }, [stream]);

  const reset = useCallback(() => {
    stopAll();
    stopWebcam();
    setPhase(PHASE.IDLE);
    setChallenge(null);
    setLog([]);
    setFramesSent(0);
    sentRef.current = 0;
  }, [stopAll, stopWebcam]);

  useEffect(() => () => { stopAll(); stopWebcam(); }, [stopAll, stopWebcam]);

  /* ── FIX BUG-4: separate camera permission step ─────────────────── */
  const requestCamera = useCallback(async () => {
    setPhase(PHASE.CAM_REQUEST);
    addLog("Requesting camera permission…");
    try {
      const s = await navigator.mediaDevices.getUserMedia({
        video: { width: 640, height: 480, facingMode: "user" },
      });
      setStream(s);
      setPhase(PHASE.CAM_READY);
      addLog("Camera granted ✓", "ok");
    } catch (e) {
      setPhase(PHASE.IDLE);
      addLog("Camera denied: " + e.message, "err");
    }
  }, []);

  /* ── FIX BUG-3: wait for canplay before drawing frames ─────────── */
  const waitForVideo = () => new Promise(resolve => {
    const vid = videoRef.current;
    if (!vid) { resolve(); return; }
    if (vid.readyState >= 2 && vid.videoWidth > 0) { resolve(); return; }
    vid.addEventListener("canplay", resolve, { once: true });
  });

  const runChallenge = useCallback(async (ch) => {
    setChallenge(ch);
    setPhase(PHASE.CHALLENGE);
    setFramesSent(0);
    sentRef.current = 0;
    runRef.current  = true;
    addLog(`Challenge: ${ch.label}`);

    /* FIX BUG-3: wait until video has actual pixel data */
    await waitForVideo();
    addLog("Video ready — streaming frames", "ok");

    const canvas = canvasRef.current;
    canvas.width  = 320;
    canvas.height = 240;
    const ctx     = canvas.getContext("2d");
    let   passed  = false;

    const submitFrame = async () => {
      if (!runRef.current) return;
      const vid = videoRef.current;

      /* FIX BUG-1 + BUG-3: only draw when video has decoded data */
      if (!vid || vid.readyState < 2 || vid.videoWidth === 0 || vid.paused) {
        addLog("Waiting for video data…");
        return;
      }

      ctx.drawImage(vid, 0, 0, 320, 240);
      const blob = await new Promise(res => canvas.toBlob(res, "image/jpeg", 0.82));
      if (!blob || !runRef.current) return;

      sentRef.current++;
      setFramesSent(sentRef.current);

      try {
        const { data } = await client.post("/camera/analyze-frame", blob, {
          headers: {
            "Content-Type":  "image/jpeg",
            "X-Challenge":   ch.id,
            "X-Session-Id":  sessionId,
            "X-Terminal-Id": "BROWSER-ADMIN",
          },
        });
        if (data?.passed) {
          passed = true;
          runRef.current = false;
          clearInterval(intervalRef.current);
          clearTimeout(timerRef.current);
          setPhase(PHASE.PASSED);
          addLog("Challenge passed ✓", "ok");
          addLog("Stored in liveness_results", "ok");
        }
      } catch (e) {
        /* network hiccup — keep streaming */
        addLog("Frame error: " + (e.response?.data?.error ?? e.message), "err");
      }
    };

    intervalRef.current = setInterval(submitFrame, FRAME_INTERVAL);

    timerRef.current = setTimeout(() => {
      if (passed) return;
      runRef.current = false;
      clearInterval(intervalRef.current);
      setPhase(PHASE.EVALUATING);
      setTimeout(() => {
        setPhase(PHASE.FAILED);
        addLog("Timeout — movement not detected ✗", "err");
      }, 800);
    }, CHALLENGE_MS);
  }, [sessionId]);

  const startChallenge = useCallback(async () => {
    stopAll();
    /* If camera isn't ready yet, request it first then wait */
    if (!stream) {
      await requestCamera();
      /* requestCamera sets stream state; effect will attach srcObject.
         Wait for canplay before kicking off the challenge. */
    }
    const ch = CHALLENGES[Math.floor(Math.random() * CHALLENGES.length)];
    runChallenge(ch);
  }, [stream, requestCamera, runChallenge, stopAll]);

  const retry = useCallback(() => {
    stopAll();
    const ch = CHALLENGES[Math.floor(Math.random() * CHALLENGES.length)];
    setLog([]);
    setFramesSent(0);
    sentRef.current = 0;
    runChallenge(ch);
  }, [stopAll, runChallenge]);

  /* ── Derived ── */
  const ringC = phase===PHASE.PASSED  ? "#34d399"
               :phase===PHASE.FAILED   ? "#f87171"
               :phase===PHASE.CHALLENGE ? (challenge?.color ?? "#818cf8")
               : "#334155";

  const isChallenge = phase === PHASE.CHALLENGE;
  const isDone      = phase === PHASE.PASSED || phase === PHASE.FAILED;

  return (
    <div className="min-h-screen bg-bg text-ink">
      {/* Header */}
      <div className="border-b border-white/5 px-6 py-5 flex items-center gap-4">
        <button type="button"
          onClick={() => window.dispatchEvent(new CustomEvent("evoting:navigate",{detail:{view:"settings"}}))}
          className="p-2 rounded-lg hover:bg-white/5 text-muted hover:text-sub transition-colors">
          <Ic n="arrow-left" s={18}/>
        </button>
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-purple-500/15 flex items-center justify-center">
            <Ic n="eye" s={20} c="#a78bfa"/>
          </div>
          <div>
            <h1 className="text-lg font-semibold">Active Liveness</h1>
            <p className="text-xs text-muted">MediaPipe challenge-response · active/app.py · port 5002</p>
          </div>
        </div>
        <div className="ml-auto px-3 py-1 rounded-full text-xs font-semibold border transition-all"
             style={{ color:ringC, borderColor:ringC+"60", background:ringC+"15" }}>
          { phase===PHASE.IDLE       ? "Idle"
          : phase===PHASE.CAM_REQUEST? "Requesting camera…"
          : phase===PHASE.CAM_READY  ? "Camera ready"
          : phase===PHASE.CHALLENGE  ? `Live · ${challenge?.label}`
          : phase===PHASE.EVALUATING ? "Analysing"
          : phase===PHASE.PASSED     ? "Passed ✓"
          : "Failed ✗" }
        </div>
      </div>

      <div className="max-w-4xl mx-auto px-6 py-8">
        <div className="grid grid-cols-1 md:grid-cols-[240px_1fr] gap-6">

          {/* ── Webcam column ── */}
          <div className="flex flex-col items-center gap-3">
            {/* Ring container — FIX BUG-1: video always block, overlay for placeholder */}
            <div className="w-[220px] h-[220px] rounded-full overflow-hidden relative flex-shrink-0 bg-black/40 transition-all duration-300"
                 style={{ border:`3px solid ${ringC}` }}>

              {/* FIX BUG-1: video is ALWAYS rendered, ALWAYS display:block.
                  Chromium/Firefox won't decode frames for display:none elements.
                  Placeholder sits as an absolute overlay instead. */}
              <video
                ref={videoRef}
                autoPlay
                muted
                playsInline
                style={{
                  width: "100%", height: "100%",
                  objectFit: "cover",
                  transform: "scaleX(-1)",
                  display: "block",          /* never hidden */
                  background: "#0f172a",
                }}
              />

              {/* Placeholder overlay when no stream */}
              {!stream && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/80">
                  <Ic n="camera-off" s={38} c="#334155"/>
                  <span className="text-xs" style={{color:"#334155"}}>Camera off</span>
                </div>
              )}

              {/* Evaluating overlay */}
              {phase===PHASE.EVALUATING && (
                <div className="absolute inset-0 bg-black/65 flex flex-col items-center justify-center gap-2">
                  <Spinner s={28}/>
                  <span className="text-xs text-white/50">Analysing…</span>
                </div>
              )}

              {/* Pass overlay */}
              {phase===PHASE.PASSED && (
                <div className="absolute inset-0 bg-black/55 flex items-center justify-center">
                  <Ic n="circle-check" s={64} c="#34d399"/>
                </div>
              )}

              {/* Fail overlay */}
              {phase===PHASE.FAILED && (
                <div className="absolute inset-0 bg-black/55 flex items-center justify-center">
                  <Ic n="circle-x" s={64} c="#f87171"/>
                </div>
              )}
            </div>

            {/* Camera status row */}
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full transition-all"
                   style={{ background: stream ? "#34d399" : "#334155",
                            boxShadow: stream ? "0 0 6px #34d399" : "none" }}/>
              <span className="text-xs text-muted">
                {stream ? "Webcam live" : "No webcam"}
              </span>
            </div>

            {/* Frame counter */}
            {isChallenge && (
              <div className="text-xs text-muted text-center">
                <span className="text-purple-400 font-mono font-semibold">{framesSent}</span> frames sent
              </div>
            )}

            {/* Session metadata */}
            <div className="w-full rounded-lg border border-white/5 bg-white/2 p-3 text-xs space-y-1.5">
              {[
                ["Session",   sessionId],
                ["Terminal",  "BROWSER-ADMIN"],
                ["Endpoint",  "/camera/analyze-frame"],
                ["Rate",      "10 fps"],
              ].map(([k,v]) => (
                <div key={k} className="flex justify-between gap-2">
                  <span className="text-white/25">{k}</span>
                  <span className="text-muted font-mono truncate max-w-[120px]">{v}</span>
                </div>
              ))}
            </div>
          </div>

          {/* ── Right panel ── */}
          <div className="flex flex-col gap-4">

            {/* IDLE */}
            {(phase===PHASE.IDLE || phase===PHASE.CAM_REQUEST) && (
              <div className="flex flex-col gap-4">
                <div className="rounded-xl border border-white/5 bg-white/2 p-5">
                  <p className="text-sm font-semibold mb-2">How it works</p>
                  <ol className="space-y-1.5 text-xs text-muted list-decimal list-inside leading-relaxed">
                    <li>Browser asks permission to open your webcam.</li>
                    <li>A random challenge is assigned (blink / smile / turn head).</li>
                    <li>Frames stream to <code className="text-purple-400">/api/camera/analyze-frame</code> at 10 fps.</li>
                    <li>Spring Boot proxies each frame to <code className="text-purple-400">active/app.py:5002</code>.</li>
                    <li>MediaPipe evaluates facial geometry and returns <code className="text-purple-400">passed: true/false</code>.</li>
                  </ol>
                </div>

                {/* FIX BUG-4: explicit camera permission button first */}
                {phase===PHASE.IDLE && (
                  <button type="button" onClick={requestCamera}
                          className="btn btn-primary btn-md w-full justify-center gap-2">
                    <Ic n="camera" s={15} c="#fff"/>
                    Allow Camera & Start
                  </button>
                )}
                {phase===PHASE.CAM_REQUEST && (
                  <div className="flex items-center justify-center gap-3 py-4 rounded-xl border border-white/5 bg-white/2">
                    <Spinner s={20}/>
                    <span className="text-sm text-muted">
                      Waiting for camera permission…
                    </span>
                  </div>
                )}
              </div>
            )}

            {/* CAM_READY — camera allowed, waiting to start */}
            {phase===PHASE.CAM_READY && (
              <div className="flex flex-col gap-4">
                <div className="rounded-xl border border-green-500/20 bg-green-500/6 p-4 flex items-center gap-3">
                  <Ic n="check" s={18} c="#34d399"/>
                  <div>
                    <p className="text-sm font-semibold text-green-400">Camera ready</p>
                    <p className="text-xs text-muted">You should see your webcam feed on the left.</p>
                  </div>
                </div>
                <button type="button" onClick={startChallenge}
                        className="btn btn-primary btn-md w-full justify-center gap-2">
                  <Ic n="player-play" s={15} c="#fff"/>
                  Start Challenge
                </button>
              </div>
            )}

            {/* CHALLENGE */}
            {isChallenge && challenge && (
              <div className="flex flex-col gap-4">
                <CountdownBar running={true} color={challenge.color}/>
                <div className="rounded-xl border p-6 flex flex-col items-center gap-4"
                     style={{ borderColor:challenge.color+"40", background:challenge.color+"08" }}>
                  <ChallengeIcon id={challenge.id} color={challenge.color}/>
                  <div className="text-center">
                    <p className="text-xl font-semibold mb-1" style={{color:challenge.color}}>
                      {challenge.instruction}
                    </p>
                    <p className="text-xs text-muted">{challenge.hint}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2 rounded-lg border border-white/5 bg-white/2 px-3 py-2 text-xs text-muted">
                  <div className="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse"/>
                  Streaming JPEG frames → Spring Boot → MediaPipe (port 5002)
                </div>
              </div>
            )}

            {/* EVALUATING */}
            {phase===PHASE.EVALUATING && (
              <div className="rounded-xl border border-white/5 bg-white/2 p-8 flex flex-col items-center gap-3">
                <Spinner s={36}/>
                <p className="text-sm text-muted">Evaluating final frames…</p>
                <p className="text-xs text-white/20">active/app.py · /analyze-frame · port 5002</p>
              </div>
            )}

            {/* PASSED */}
            {phase===PHASE.PASSED && (
              <div className="rounded-xl border border-green-500/30 bg-green-500/6 p-6 flex flex-col items-center gap-3">
                <Ic n="circle-check" s={52} c="#34d399"/>
                <p className="text-xl font-semibold text-green-400">Challenge Passed</p>
                <p className="text-xs text-muted text-center max-w-xs">
                  Liveness result stored in <code className="text-purple-400">liveness_results</code>.
                  The S3 terminal can call <code className="text-purple-400">getLivenessResult(sessionId)</code>.
                </p>
                <div className="flex gap-3 w-full mt-1">
                  <button type="button" onClick={retry}
                          className="btn btn-sm flex-1 justify-center gap-1.5">
                    <Ic n="refresh" s={12}/> New challenge
                  </button>
                  <button type="button" onClick={reset}
                          className="btn btn-sm flex-1 justify-center gap-1.5">
                    <Ic n="x" s={12}/> Reset
                  </button>
                </div>
              </div>
            )}

            {/* FAILED */}
            {phase===PHASE.FAILED && (
              <div className="rounded-xl border border-red-500/30 bg-red-500/6 p-6 flex flex-col items-center gap-3">
                <Ic n="circle-x" s={52} c="#f87171"/>
                <p className="text-xl font-semibold text-red-400">Challenge Failed</p>
                <p className="text-xs text-muted text-center max-w-xs">
                  Movement not detected in the 10 s window. Check lighting, camera angle,
                  and that the active/app.py service is running on port 5002.
                </p>
                <button type="button" onClick={retry}
                        className="btn btn-primary btn-sm w-full justify-center gap-1.5 mt-1">
                  <Ic n="player-play" s={13}/> Try Again
                </button>
              </div>
            )}

            <Log entries={log}/>

            {/* Threshold reference */}
            {!isDone && (
              <div className="rounded-xl border border-white/5 bg-white/2 p-4 mt-auto">
                <p className="text-xs font-semibold text-muted mb-2 uppercase tracking-wide">Detection thresholds</p>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  {[
                    ["Head turn",  "nose/cheek ratio < 0.45", "#818cf8"],
                    ["Blink",      "avg eye gap < 0.012",      "#f59e0b"],
                    ["Smile",      "lip gap > 0.04 or spread > 0.12", "#34d399"],
                    ["Window",     "10 s per challenge",       "#64748b"],
                  ].map(([k,v,c]) => (
                    <div key={k} className="rounded-lg border border-white/5 p-2.5">
                      <p className="font-semibold mb-0.5" style={{color:c}}>{k}</p>
                      <p className="text-white/35 leading-relaxed">{v}</p>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
