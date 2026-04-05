import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
import io

app = FastAPI(title="Liveness Detection Service")

# Point to the exact model we downloaded via curl
MODEL_PATH = "models/MiniFASNetV2_SE.onnx"
ort_session = None

@app.on_event("startup")
def load_models():
    global ort_session
    print(f"Loading Liveness Model from {MODEL_PATH}...")
    try:
        # Initialize the ONNX Runtime engine
        ort_session = ort.InferenceSession(MODEL_PATH)
        print("Model loaded successfully!")
    except Exception as e:
        print(f"CRITICAL ERROR loading model: {e}")
        raise e

@app.get("/health")
def health_check():
    return {"status": "Liveness AI Engine is online and ready"}

@app.post("/evaluate")
async def evaluate_liveness(frame: UploadFile = File(...)):
    global ort_session
    try:
        # 1. Read the image payload from the request (from ESP32 / Spring Boot)
        image_bytes = await frame.read()
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if img is None:
            return JSONResponse(status_code=400, content={"error": "Invalid image payload"})

        # 2. Preprocess the image for MiniFASNet (Resize to 80x80)
        img = cv2.resize(img, (128, 128))
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = np.transpose(img, (2, 0, 1)) # Change format from HWC to CHW
        img = np.expand_dims(img, axis=0)
        img = img.astype(np.float32) / 255.0 # Normalize

        # 3. Run AI Inference
        input_name = ort_session.get_inputs()[0].name
        outputs = ort_session.run(None, {input_name: img})

        # 4. Parse the Softmax Output (Index 1 is usually the 'Real Face' score)
        prediction = outputs[0][0]
        real_score = float(prediction[1])

        # 5. Make the Liveness Decision (Threshold set to 80% confidence)
        is_real = real_score > 0.80

        print(f"Processed Frame - Real Confidence: {real_score*100:.2f}% | Passed: {is_real}")

        return {
            "livenessPassed": is_real,
            "confidenceScore": real_score
        }

    except Exception as e:
        print(f"Inference error: {e}")
        return JSONResponse(status_code=500, content={"error": "Internal AI server error"})