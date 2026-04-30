"""
main.py — MFA E-Voting Liveness Detection Service (V3)
=======================================================
Upgrade summary over V2:

  V2 problems fixed:
  ─ Model received the entire 320×240 scene resized to 80×80 (no face crop).
    MiniFASNet is trained on pre-cropped 80×80 faces; full-scene input puts
    it completely out of distribution and produces random-looking scores.
  ─ Single threshold 0.80 for MiniFASNet was applied to a model that was not
    running correctly, so the threshold was also wrong.
  ─ No optical flow: a printed photo with good lighting passes every
    single-frame model with sufficient confidence eventually.

  V3 additions:
  ✓ Ultraface RFB-320 face detector — crops the face before inference.
  ✓ Three-scale MiniFASNet inference (the paper's intended usage).
  ✓ Optional CDCN++ model (activate via LIVENESS_MODEL=cdcn in .env).
  ✓ /evaluate-burst endpoint — accepts 5 JPEG frames, adds optical flow
    motion score fused with model score.
  ✓ /evaluate (single frame) kept for backward compatibility during
    staged firmware rollout. All terminals should migrate to burst mode.

Endpoints:
  GET  /health                — service health (no auth)
  POST /evaluate              — single JPEG frame (V2 compat)
  POST /evaluate-burst        — 5 JPEG frames (V3, preferred)
"""

import io
import os
import logging

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, UploadFile, Header, HTTPException
from fastapi.responses import JSONResponse

import config
from liveness_evaluator import LivenessEvaluator

import base64



# ─── Logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s"
)
log = logging.getLogger("liveness-service")

# ─── App ──────────────────────────────────────────────────────────────────────
app = FastAPI(
    title="MFA E-Voting Liveness Detection Service",
    version="3.0.0"
)

_evaluator: LivenessEvaluator | None = None


@app.on_event("startup")
def startup():
    global _evaluator
    if not config.LIVENESS_SECRET:
        log.critical("LIVENESS_SECRET is not set — all /evaluate* calls will return 503. "
                     "Set LIVENESS_SECRET in liveness-service/.env and in "
                     "Spring Boot application.yml (liveness.service.secret).")
    log.info("Loading liveness models (mode=%s)...", config.LIVENESS_MODEL)
    _evaluator = LivenessEvaluator()
    log.info("Liveness service ready.")


# ─── Auth helper ──────────────────────────────────────────────────────────────

def _require_secret(provided: str | None) -> None:
    if not config.LIVENESS_SECRET:
        raise HTTPException(status_code=503,
                            detail="Liveness service is misconfigured: LIVENESS_SECRET not set.")
    if provided != config.LIVENESS_SECRET:
        raise HTTPException(status_code=403, detail="Forbidden")


def _decode_jpeg(data: bytes) -> np.ndarray | None:
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    return img


# ─── Endpoints ────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":       "ok",
        "version":      "3.0.0",
        "model":        config.LIVENESS_MODEL,
        "burst_support": True
    }


@app.post("/evaluate")
async def evaluate_single(
        frame:              UploadFile = File(...),
        x_liveness_secret:  str | None = Header(default=None, alias="X-Liveness-Secret"),
        x_session_id:       str | None = Header(default=None, alias="X-Session-Id"),
        x_terminal_id:      str | None = Header(default=None, alias="X-Terminal-Id"),
):
    """
    Single-frame liveness endpoint (V2-compatible).
    Runs Ultraface face detection + multi-scale MiniFASNet (or CDCN++).
    Kept for backward compatibility; migrate terminals to /evaluate-burst.
    """
    _require_secret(x_liveness_secret)

    data = await frame.read()
    img  = _decode_jpeg(data)

    if img is None:
        return JSONResponse(status_code=400, content={"error": "Invalid JPEG payload"})

    result = _evaluator.evaluate(img)
    log.info("[single] session=%s terminal=%s passed=%s score=%.4f model=%s detail=%s",
             x_session_id, x_terminal_id,
             result["livenessPassed"], result["confidenceScore"],
             result["model"], result["detail"])

    return result


@app.post("/evaluate-burst")
async def evaluate_burst(
        frame0:             UploadFile = File(...),
        frame1:             UploadFile = File(...),
        frame2:             UploadFile = File(...),
        frame3:             UploadFile = File(...),
        frame4:             UploadFile = File(...),
        x_liveness_secret:  str | None = Header(default=None, alias="X-Liveness-Secret"),
        x_session_id:       str | None = Header(default=None, alias="X-Session-Id"),
        x_terminal_id:      str | None = Header(default=None, alias="X-Terminal-Id"),
):
    """
    5-frame burst liveness endpoint (V3, preferred).

    The ESP32-CAM captures 5 JPEG frames at 200 ms intervals and POSTs them
    as a single multipart request.

    Decision is the weighted fusion of:
      - Single-frame liveness model score (middle frame, weight=LIVENESS_WEIGHT)
      - Optical flow motion score across all 5 frames  (weight=FLOW_WEIGHT)

    Both scores must individually pass their minimums AND the fused score
    must exceed BURST_FUSED_THRESHOLD. A near-zero flow score flags a static
    image attack regardless of model confidence.
    """
    _require_secret(x_liveness_secret)

    raw_uploads = [frame0, frame1, frame2, frame3, frame4]
    frames: list[np.ndarray] = []

    for i, upload in enumerate(raw_uploads):
        data = await upload.read()
        img  = _decode_jpeg(data)
        if img is None:
            log.warning("[burst] session=%s invalid JPEG at frame%d — returning fail",
                        x_session_id, i)
            return JSONResponse(
                status_code=400,
                content={"error": f"Invalid JPEG payload in frame{i}"}
            )
        frames.append(img)

    result = _evaluator.evaluate_burst(frames)
    log.info("[burst] session=%s terminal=%s passed=%s fused=%.4f "
             "liveness=%.4f flow=%.4f model=%s detail=%s",
             x_session_id,       x_terminal_id,
             result["livenessPassed"],    result["confidenceScore"],
             result.get("livenessScore", 0), result.get("flowScore", 0),
             result["model"],             result["detail"])

    return result
# ── Paste these two functions into main.py ────────────────────────────────────

@app.post("/debug/preview")
async def debug_preview(
        frame:              UploadFile = File(...),
        x_liveness_secret:  str | None = Header(default=None, alias="X-Liveness-Secret"),
):
    """
    Debug endpoint: annotates face bbox, runs liveness inference, returns
    base64-encoded annotated JPEG + all intermediate scores.

    REMOVE THIS ENDPOINT BEFORE DEPLOYING TO PRODUCTION / ELECTION DAY.
    It returns face image data over the wire — privacy liability.
    """
    _require_secret(x_liveness_secret)

    data = await frame.read()
    img  = _decode_jpeg(data)
    if img is None:
        return JSONResponse(status_code=400,
                            content={"error": "Invalid JPEG payload"})

    # Detect face
    face = _evaluator._detector.detect(img)

    # Annotate
    annotated = img.copy()
    if face:
        # Bounding box
        cv2.rectangle(annotated,
                      (face.x1, face.y1), (face.x2, face.y2),
                      (139, 92, 246), 2)  # Purple
        # Score badge
        label = f"face {face.score:.2f}"
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        cv2.rectangle(annotated,
                      (face.x1, face.y1 - th - 8),
                      (face.x1 + tw + 6, face.y1),
                      (139, 92, 246), -1)
        cv2.putText(annotated, label,
                    (face.x1 + 3, face.y1 - 4),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (240, 236, 255), 1)

        # Draw 3 crop scales used in multi-scale inference
        for margin, color in [(1.35, (167, 139, 250)), (2.0, (96, 165, 250))]:
            import face_detector as fd_module
            crop_img = _evaluator._detector.crop_face(img, face,
                                                      margin_factor=margin,
                                                      target_size=80)
            h, w = img.shape[:2]
            cx = (face.x1 + face.x2) // 2
            cy = (face.y1 + face.y2) // 2
            half = int(max(face.width, face.height) * margin / 2)
            x1 = max(0, cx - half); x2 = min(w, cx + half)
            y1 = max(0, cy - half); y2 = min(h, cy + half)
            cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 1)

    # Run liveness
    result = _evaluator.evaluate(img)

    # Encode
    _, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 88])
    b64 = base64.b64encode(buf).decode()

    return {
        "image_b64":      b64,
        "face_detected":  face is not None,
        "face_score":     float(face.score) if face else 0.0,
        "livenessPassed": result["livenessPassed"],
        "confidenceScore":result["confidenceScore"],
        "model":          result["model"],
        "detail":         result["detail"],
    }


@app.get("/health/detail")
async def health_detail(
        x_liveness_secret: str | None = Header(default=None, alias="X-Liveness-Secret"),
):
    """
    Extended health check for the admin dashboard Model Status tab.
    Returns model file presence, sizes, and active configuration.
    """
    _require_secret(x_liveness_secret)

    import config

    def file_info(path):
        if os.path.exists(path):
            return {"present": True, "size_kb": os.path.getsize(path) // 1024}
        return {"present": False, "size_kb": 0}

    return {
        "status":        "ok",
        "version":       "3.0.0",
        "model":         config.LIVENESS_MODEL,
        "burst_support": True,
        "models": {
            "MiniFASNetV2":   file_info(config.MINIFASNET_V2_PATH),
            "MiniFASNetV1SE": file_info(config.MINIFASNET_V1SE_PATH),
            "FaceDetector":   file_info(config.FACE_DETECTOR_PATH),
            "CDCNpp":         file_info(config.CDCN_PATH),
        },
        "thresholds": {
            "minifasnet":    config.MINIFASNET_THRESHOLD,
            "cdcn":          config.CDCN_THRESHOLD,
            "burst_fused":   config.BURST_FUSED_THRESHOLD,
            "flow_min":      config.FLOW_MIN_MOTION,
            "flow_weight":   config.FLOW_WEIGHT,
            "liveness_weight": config.LIVENESS_WEIGHT,
        },
    }

# ─── Render / local entry point ───────────────────────────────────────────────
# Render injects $PORT dynamically. Locally defaults to 5001.
# Always bind 0.0.0.0 so Render's routing layer can reach the process.
if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    uvicorn.run("main:app", host="0.0.0.0", port=port)
