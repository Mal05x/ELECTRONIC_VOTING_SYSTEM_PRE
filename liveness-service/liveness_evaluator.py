"""
liveness_evaluator.py — Liveness model inference with face detection.

Two modes (set LIVENESS_MODEL in .env / Render environment):

  "minifasnet_multiscale" (default)
    Runs two MiniFASNet models at their training crop scales and averages:
      - MiniFASNetV2   at crop margin 1.35 (paper scale 2.7)
      - MiniFASNetV1SE at crop margin 2.0  (paper scale 4.0)
    This is the correct multi-scale inference from the Silent-Face paper —
    different models trained at different scales, not one model at arbitrary crops.

  "cdcn"
    Runs CDCN++ on a 256x256 aligned face crop. The model outputs a depth map;
    real faces have high-valued maps, spoofs have near-zero maps.
    Requires running cdcn_export.py first to produce models/CDCNpp.onnx.
    Falls back to minifasnet_multiscale if CDCNpp.onnx is absent.
"""
import cv2
import numpy as np
import os
import onnxruntime as ort
from typing import Optional
import config
from face_detector import FaceDetector, FaceBBox


class LivenessEvaluator:

    def __init__(self):
        self._detector = FaceDetector()
        # List of (ort.InferenceSession, margin_factor, weight) tuples
        self._fas_models: list[tuple[ort.InferenceSession, float, float]] = []
        self._cdcn_session: Optional[ort.InferenceSession] = None
        self._mode = config.LIVENESS_MODEL
        self._load_models()

    def _load_models(self):
        # Load MiniFASNet models — always needed (fallback for cdcn mode too)
        for (path, margin, weight) in config.MINIFASNET_SCALE_CONFIG:
            if os.path.exists(path):
                sess = ort.InferenceSession(path)
                self._fas_models.append((sess, margin, weight))
                print(f"[LivenessEvaluator] Loaded {os.path.basename(path)} "
                      f"(margin={margin}, weight={weight})")
            else:
                print(f"[LivenessEvaluator] WARNING: {path} not found. "
                      f"Run download_models.py")

        if not self._fas_models:
            raise RuntimeError(
                "No MiniFASNet models could be loaded. "
                "Run python download_models.py to download required models."
            )

        # Load CDCN++ only if selected
        if self._mode == "cdcn":
            if os.path.exists(config.CDCN_PATH):
                self._cdcn_session = ort.InferenceSession(config.CDCN_PATH)
                print(f"[LivenessEvaluator] CDCN++ loaded from {config.CDCN_PATH}")
            else:
                print(f"[LivenessEvaluator] CDCN++ not found at {config.CDCN_PATH}. "
                      f"Run cdcn_export.py. Falling back to minifasnet_multiscale.")
                self._mode = "minifasnet_multiscale"

    # ─── Public API ──────────────────────────────────────────────────────────

    def evaluate(self, bgr_img: np.ndarray) -> dict:
        """Single-frame liveness evaluation."""
        face = self._detector.detect(bgr_img)
        if face is None:
            return {
                "livenessPassed": False, "confidenceScore": 0.0,
                "faceDetected": False,   "model": self._mode,
                "detail": "no_face_detected"
            }
        score, threshold = self._infer(bgr_img, face)
        passed = score >= threshold
        return {
            "livenessPassed":  passed,
            "confidenceScore": round(score, 4),
            "faceDetected":    True,
            "model":           self._mode,
            "detail":          "passed" if passed else "spoof_detected"
        }

    def evaluate_burst(self, frames: list[np.ndarray]) -> dict:
        """Multi-frame burst liveness evaluation with optical flow fusion."""
        from optical_flow import compute_burst_flow_score

        mid    = len(frames) // 2
        single = self.evaluate(frames[mid])

        if not single["faceDetected"]:
            return {**single, "flowScore": 0.0, "burstFrames": len(frames)}

        flow_score     = compute_burst_flow_score(frames)
        liveness_score = single["confidenceScore"]
        fused = (liveness_score * config.LIVENESS_WEIGHT +
                 flow_score     * config.FLOW_WEIGHT)

        passed = (
                fused    >= config.BURST_FUSED_THRESHOLD and
                single["livenessPassed"]                  and
                flow_score >= config.FLOW_MIN_MOTION
        )

        if not passed:
            detail = "static_image_suspected" if flow_score < config.FLOW_MIN_MOTION \
                else "spoof_detected"
        else:
            detail = "passed"

        return {
            "livenessPassed":  passed,
            "confidenceScore": round(fused, 4),
            "livenessScore":   round(liveness_score, 4),
            "flowScore":       round(flow_score, 4),
            "faceDetected":    True,
            "burstFrames":     len(frames),
            "model":           self._mode,
            "detail":          detail
        }

    # ─── Private inference ───────────────────────────────────────────────────

    def _infer(self, bgr_img: np.ndarray, face: FaceBBox) -> tuple[float, float]:
        if self._mode == "cdcn" and self._cdcn_session is not None:
            return self._eval_cdcn(bgr_img, face), config.CDCN_THRESHOLD
        return self._eval_minifasnet_multiscale(bgr_img, face), config.MINIFASNET_THRESHOLD

    def _eval_minifasnet_multiscale(self, bgr_img: np.ndarray,
                                    face: FaceBBox) -> float:
        """
        Run each loaded MiniFASNet model at its training crop scale.
        Returns the weighted average real-class softmax score.

        MiniFASNetV2   (margin 1.35, paper scale 2.7) — tighter crop, fine texture
        MiniFASNetV1SE (margin 2.0,  paper scale 4.0) — wider crop, global context
        """
        weighted_sum  = 0.0
        weight_total  = 0.0

        for (sess, margin, weight) in self._fas_models:
            crop   = self._detector.crop_face(bgr_img, face,
                                              margin_factor=margin,
                                              target_size=80)
            tensor = self._preprocess_minifasnet(crop)
            output = sess.run(None, {sess.get_inputs()[0].name: tensor})[0][0]

            # output[1] = real-class softmax probability
            real_prob     = float(np.clip(output[1], 0.0, 1.0))
            weighted_sum  += real_prob * weight
            weight_total  += weight

        return weighted_sum / weight_total if weight_total > 0 else 0.0

    def _eval_cdcn(self, bgr_img: np.ndarray, face: FaceBBox) -> float:
        """CDCN++ inference — mean depth map value as liveness score."""
        crop = self._detector.crop_face(bgr_img, face,
                                        margin_factor=1.3,
                                        target_size=256)
        rgb  = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
        std  = np.array([0.229, 0.224, 0.225], dtype=np.float32)
        norm = (rgb - mean) / std
        tensor     = np.expand_dims(norm.transpose(2, 0, 1), axis=0).astype(np.float32)
        input_name = self._cdcn_session.get_inputs()[0].name
        depth_map  = self._cdcn_session.run(None, {input_name: tensor})[0]
        return float(np.mean(depth_map))

    @staticmethod
    def _preprocess_minifasnet(bgr_crop: np.ndarray) -> np.ndarray:
        """80x80 BGR crop → (1, 3, 80, 80) float32 tensor in [0, 1]."""
        rgb = cv2.cvtColor(bgr_crop, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        return np.expand_dims(rgb.transpose(2, 0, 1), axis=0)
