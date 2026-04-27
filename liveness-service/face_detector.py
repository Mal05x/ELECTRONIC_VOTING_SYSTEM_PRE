"""
face_detector.py — Ultraface version-RFB-320 ONNX face detector.

Why Ultraface instead of SCRFD or Haar cascades:
  - version-RFB-320.onnx is designed for EXACTLY 320×240 input — the native
    resolution the ESP32-CAM sends. Zero upscaling needed.
  - Model size ~1.1 MB, inference ~25 ms on CPU. Negligible overhead.
  - Stable public GitHub release download (no Baidu Drive / Onedrive required).

Source: https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB

crop_face() supports a margin_factor to expand the bbox for multi-scale
MiniFASNet inference. margin_factor=1.0 is a tight face crop; 2.0 includes
a full face-width of surrounding context on each side.
"""
import cv2
import numpy as np
import onnxruntime as ort
from dataclasses import dataclass
from typing import Optional
import config


@dataclass
class FaceBBox:
    x1: int
    y1: int
    x2: int
    y2: int
    score: float

    @property
    def width(self) -> int:  return self.x2 - self.x1

    @property
    def height(self) -> int: return self.y2 - self.y1

    @property
    def area(self) -> int:   return self.width * self.height


class FaceDetector:
    """
    Ultraface RFB-320 — optimised for 320×240 BGR input.
    Returns the single highest-confidence face in the image, or None.
    """

    # Ultraface model input dimensions (must match model)
    INPUT_W = 320
    INPUT_H = 240

    # Per-channel normalisation constants (from original repo)
    MEAN = np.array([127.0, 127.0, 127.0], dtype=np.float32)
    STD  = 128.0

    def __init__(self, model_path: str = config.FACE_DETECTOR_PATH):
        self._session    = ort.InferenceSession(model_path)
        self._input_name = self._session.get_inputs()[0].name
        print(f"[FaceDetector] Loaded {model_path}")

    # ─── Public API ──────────────────────────────────────────────────────────

    def detect(self, bgr_img: np.ndarray) -> Optional[FaceBBox]:
        """
        Detect the best face in bgr_img.
        Returns None if no face exceeds config.FACE_CONFIDENCE_THRESHOLD or
        if the detected face is smaller than config.FACE_MIN_SIZE_PX.
        """
        h, w = bgr_img.shape[:2]

        # Resize to model input size (320×240) — cheap if already QVGA
        resized = cv2.resize(bgr_img, (self.INPUT_W, self.INPUT_H))
        rgb     = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32)
        norm    = (rgb - self.MEAN) / self.STD

        # CHW layout, add batch dimension
        tensor = np.expand_dims(norm.transpose(2, 0, 1), axis=0)  # (1, 3, 240, 320)

        # confidences: (1, N, 2)   boxes: (1, N, 4) — normalised [x1,y1,x2,y2]
        confidences, boxes = self._session.run(None, {self._input_name: tensor})

        face_scores = confidences[0, :, 1]   # class index 1 = face
        best        = int(np.argmax(face_scores))

        if face_scores[best] < config.FACE_CONFIDENCE_THRESHOLD:
            return None

        # De-normalise to original image pixel coordinates
        bx1, by1, bx2, by2 = boxes[0, best]
        x1 = int(bx1 * w);  x2 = int(bx2 * w)
        y1 = int(by1 * h);  y2 = int(by2 * h)

        face = FaceBBox(
            x1=max(0, x1), y1=max(0, y1),
            x2=min(w, x2), y2=min(h, y2),
            score=float(face_scores[best])
        )

        if face.width < config.FACE_MIN_SIZE_PX or face.height < config.FACE_MIN_SIZE_PX:
            return None

        return face

    def crop_face(self, bgr_img: np.ndarray, face: FaceBBox,
                  margin_factor: float = 1.0,
                  target_size: int = 80) -> np.ndarray:
        """
        Crop and resize the face region with an optional margin expansion.

        The crop is computed from the bbox centre, so the resulting square
        has side = max(face.width, face.height) × margin_factor.

        margin_factor=1.0 → tight crop (just the face)
        margin_factor=1.5 → 50% extra context per side
        margin_factor=2.0 → face width of context per side

        Returns a (target_size × target_size) BGR image.
        """
        h, w = bgr_img.shape[:2]
        cx   = (face.x1 + face.x2) // 2
        cy   = (face.y1 + face.y2) // 2
        half = int(max(face.width, face.height) * margin_factor / 2)

        x1 = max(0, cx - half);  x2 = min(w, cx + half)
        y1 = max(0, cy - half);  y2 = min(h, cy + half)

        crop = bgr_img[y1:y2, x1:x2]
        if crop.size == 0:
            crop = bgr_img   # Fallback: use full frame rather than empty crop

        return cv2.resize(crop, (target_size, target_size))
