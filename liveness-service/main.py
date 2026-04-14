import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, File, UploadFile, Header, HTTPException
from fastapi.responses import JSONResponse
import io
import os

app = FastAPI(title="Liveness Detection Service")

MODEL_PATH = "models/MiniFASNetV2_SE.onnx"
ort_session = None

# ── FIX DQ2: Shared-secret authentication ────────────────────────────────────
# The /evaluate endpoint is no longer open to the internal network.
# BiometricService must include the X-Liveness-Secret header on every call.
# Set LIVENESS_SECRET in the liveness-service/.env and in Spring's application.yml
# (liveness.service.secret). Use: openssl rand -hex 32
LIVENESS_SECRET = os.environ.get("LIVENESS_SECRET", "")

@app.on_event("startup")
def load_models():
    global ort_session
    if not LIVENESS_SECRET:
        print("CRITICAL: LIVENESS_SECRET env var is not set. "
              "All requests to /evaluate will be rejected.")
    print(f"Loading Liveness Model from {MODEL_PATH}...")
    try:
        ort_session = ort.InferenceSession(MODEL_PATH)
        print("Model loaded successfully!")
    except Exception as e:
        print(f"CRITICAL ERROR loading model: {e}")
        raise e

@app.get("/health")
def health_check():
    # Health endpoint intentionally has no auth — Spring boot checks it on startup
    return {"status": "Liveness AI Engine is online and ready"}

@app.post("/evaluate")
async def evaluate_liveness(
        frame: UploadFile = File(...),
        x_liveness_secret: str = Header(default=None, alias="X-Liveness-Secret"),
        x_session_id: str = Header(default=None, alias="X-Session-Id"),
        x_terminal_id: str = Header(default=None, alias="X-Terminal-Id"),
):
    # ── FIX DQ2: Validate shared secret ──────────────────────────────────────
    if not LIVENESS_SECRET:
        raise HTTPException(status_code=503,
                            detail="Liveness service is misconfigured: LIVENESS_SECRET not set.")
    if x_liveness_secret != LIVENESS_SECRET:
        # Return 403, not 401 — don't hint at auth scheme details
        raise HTTPException(status_code=403, detail="Forbidden")

    global ort_session
    try:
        image_bytes = await frame.read()
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if img is None:
            return JSONResponse(status_code=400, content={"error": "Invalid image payload"})

        # ── FIX BUG 1: Correct input resolution for MiniFASNetV2_SE ──────────
        # MiniFASNetV2_SE expects 80×80 input, not 128×128.
        # The incorrect (128,128) resize causes the model to receive inputs outside
        # its training distribution, producing unreliable liveness scores silently.
        img = cv2.resize(img, (80, 80))           # was: (128, 128) ← BUG FIXED
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = np.transpose(img, (2, 0, 1))        # HWC → CHW
        img = np.expand_dims(img, axis=0)
        img = img.astype(np.float32) / 255.0      # normalize to [0, 1]

        input_name = ort_session.get_inputs()[0].name
        outputs = ort_session.run(None, {input_name: img})

        # Index 1 = 'Real Face' score in MiniFASNet softmax output
        prediction = outputs[0][0]
        real_score = float(prediction[1])

        is_real = real_score > 0.80

        print(f"[Session={x_session_id}] Real: {real_score*100:.2f}% | Passed: {is_real}")

        return {
            "livenessPassed": is_real,
            "confidenceScore": real_score
        }

    except Exception as e:
        print(f"Inference error: {e}")
        return JSONResponse(status_code=500, content={"error": "Internal AI server error"})
