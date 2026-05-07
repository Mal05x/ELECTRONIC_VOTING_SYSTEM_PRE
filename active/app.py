"""
active/app.py — MediaPipe Active Liveness Service v1.2
=======================================================
Serves the challenge-response (active) liveness model on port 5002.
Port 5001 is reserved for the passive MiniFASNet liveness service.

Fixes over v1.0:
  BUG-1  No authentication — X-Liveness-Secret now required (mirrors passive service).
  BUG-2  Port conflict — moved from 5001 to 5002.
  BUG-3  Null/IndexError on landmark access — wrapped in try/except.
  BUG-4  Flask dev server used in production — gunicorn entry point added.
  BUG-5  X-Challenge not validated — only VALID_CHALLENGES accepted.
  BUG-6  Blink used only left eye — now averages both eyes (more robust).
  BUG-7  Smile detection used only lip gap — now also checks mouth-corner spread.
  BUG-8  Head-turn threshold 0.40 was too aggressive — tuned to 0.45.
"""

import os
import cv2
import mediapipe as mp
import numpy as np
from flask import Flask, request, jsonify

app = Flask(__name__)

# ── Security ─────────────────────────────────────────────────────────────────
LIVENESS_SECRET: str = os.environ.get("LIVENESS_SECRET", "")
VALID_CHALLENGES = {"TURN_HEAD_LEFT", "TURN_HEAD_RIGHT", "SMILE", "BLINK"}

# ── MediaPipe Face Mesh ───────────────────────────────────────────────────────
mp_face_mesh = mp.solutions.face_mesh
face_mesh = mp_face_mesh.FaceMesh(
    max_num_faces=1,
    refine_landmarks=True,
    min_detection_confidence=0.6,
    min_tracking_confidence=0.6
)


def _dist(p1, p2) -> float:
    return float(np.sqrt((p1.x - p2.x) ** 2 + (p1.y - p2.y) ** 2))


def _require_secret():
    if not LIVENESS_SECRET:
        return jsonify({"error": "Service misconfigured: LIVENESS_SECRET not set"}), 503
    provided = request.headers.get("X-Liveness-Secret", "")
    if not provided or provided != LIVENESS_SECRET:
        return jsonify({"error": "Forbidden"}), 403
    return None


@app.get("/health")
def health():
    return jsonify({"status": "ok", "service": "active-liveness", "version": "1.2.0", "port": 5002})


@app.post("/analyze-frame")
def analyze_frame():
    err = _require_secret()
    if err:
        return err

    session_id = request.headers.get("X-Session-Id", "unknown")
    challenge  = request.headers.get("X-Challenge", "").strip().upper()

    if challenge not in VALID_CHALLENGES:
        return jsonify({"passed": False, "error": f"Unknown challenge '{challenge}'"}), 400

    img_bytes = np.frombuffer(request.data, np.uint8)
    img = cv2.imdecode(img_bytes, cv2.IMREAD_COLOR)
    if img is None:
        return jsonify({"passed": False, "error": "Invalid JPEG"}), 400

    rgb     = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    results = face_mesh.process(rgb)
    if not results.multi_face_landmarks:
        return jsonify({"passed": False, "message": "No face detected"})

    landmarks = results.multi_face_landmarks[0].landmark
    passed    = False

    try:
        if challenge in ("TURN_HEAD_LEFT", "TURN_HEAD_RIGHT"):
            nose_tip    = landmarks[1]
            left_cheek  = landmarks[234]
            right_cheek = landmarks[454]
            ld = _dist(nose_tip, left_cheek)
            rd = _dist(nose_tip, right_cheek)
            passed = (ld < rd * 0.45) if challenge == "TURN_HEAD_LEFT" else (rd < ld * 0.45)

        elif challenge == "SMILE":
            mouth_open  = _dist(landmarks[13], landmarks[14])
            mouth_width = _dist(landmarks[61], landmarks[291])
            passed = (mouth_open > 0.040) or (mouth_width > 0.120)

        elif challenge == "BLINK":
            avg_gap = (_dist(landmarks[159], landmarks[145]) + _dist(landmarks[386], landmarks[374])) / 2.0
            passed  = avg_gap < 0.012

    except (IndexError, AttributeError) as exc:
        return jsonify({"passed": False, "error": f"Landmark error: {exc}"}), 500

    if passed:
        print(f"[active] [{session_id}] PASSED challenge={challenge}")

    return jsonify({"passed": passed, "challenge": challenge})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5002))
    print(f"[active-liveness] Starting on port {port}")
    app.run(host="0.0.0.0", port=port, debug=False)
