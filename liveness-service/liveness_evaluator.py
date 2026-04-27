"""
liveness_evaluator.py — Liveness model inference with face detection.

Two modes (set LIVENESS_MODEL in .env):
  "minifasnet_multiscale" (default)
    Runs MiniFASNetV2_SE at three crop scales around the detected face and
    takes the weighted average of the real-class softmax scores.
    This is the correct way to use MiniFASNet per the original paper.
    The v2 pipeline was running the model on the entire 320×240 frame
    resized to 80×80 — that is NOT how the model was trained and it produced
    systematically unreliable scores.

  "cdcn"
    Runs CDCN++ on a 256×256 aligned face crop. The model outputs a depth
    map; real faces have high-valued depth maps, spoofs have near-zero maps.
    Requires running cdcn_export.py first to produce models/CDCNpp.onnx.
    Falls back to minifasnet_multiscale if CDCNpp.onnx is absent.

Both modes share the same Ultraface face detector preprocessing step.
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
        self._detector    = FaceDetector()
        self._fas_session: Optional[ort.InferenceSession] = None
        self._cdcn_session: Optional[ort.InferenceSession] = None
        self._mode        = config.LIVENESS_MODEL
        self._load_models()

    def _load_models(self):
        # Always load MiniFASNet — it is the fallback regardless of mode
        if os.path.exists(config.MINIFASNET_PATH):
            self._fas_session = ort.InferenceSession(config.MINIFASNET_PATH)
            print(f"[LivenessEvaluator] MiniFASNet loaded from {config.MINIFASNET_PATH}")
        else:
            print(f"[LivenessEvaluator] WARNING: MiniFASNet not found at "
                  f"{config.MINIFASNET_PATH}. Run download_models.py")

        # Load CDCN++ only if selected
        if self._mode == "cdcn":
            if os.path.exists(config.CDCN_PATH):
                self._cdcn_session = ort.InferenceSession(config.CDCN_PATH)
                print(f"[LivenessEvaluator] CDCN++ loaded from {config.CDCN_PATH}")
            else:
                print(f"[LivenessEvaluator] CDCN++ model not found at {config.CDCN_PATH}. "
                      f"Run cdcn_export.py to generate it. "
                      f"Falling back to minifasnet_multiscale.")
                self._mode = "minifasnet_multiscale"

    # ─── Public API ──────────────────────────────────────────────────────────

    def evaluate(self, bgr_img: np.ndarray) -> dict:
        """
        Single-frame liveness evaluation.
        Detects face first, then runs the configured liveness model.
        """
        face = self._detector.detect(bgr_img)

        if face is None:
            return {
                "livenessPassed": False,
                "confidenceScore": 0.0,
                "faceDetected": False,
                "model": self._mode,
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
        """
        Multi-frame liveness evaluation (burst mode).
        Combines single-frame model score on the middle frame with the
        optical flow motion score across all frames.
        """
        from optical_flow import compute_burst_flow_score

        # Use the middle frame for single-frame liveness inference
        mid    = len(frames) // 2
        single = self.evaluate(frames[mid])

        if not single["faceDetected"]:
            return {
                **single,
                "flowScore":   0.0,
                "burstFrames": len(frames),
            }

        flow_score     = compute_burst_flow_score(frames)
        liveness_score = single["confidenceScore"]

        # Weighted fusion
        fused  = (liveness_score * config.LIVENESS_WEIGHT +
                  flow_score     * config.FLOW_WEIGHT)

        # Both conditions must hold:
        #   1. Fused score exceeds threshold
        #   2. Single-frame model voted real
        #   3. Flow score is above FLOW_MIN_MOTION (not static)
        passed = (
                fused    >= config.BURST_FUSED_THRESHOLD and
                single["livenessPassed"]                  and
                flow_score >= config.FLOW_MIN_MOTION
        )

        if not passed:
            if flow_score < config.FLOW_MIN_MOTION:
                detail = "static_image_suspected"
            else:
                detail = "spoof_detected"
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

    # ─── Private inference methods ────────────────────────────────────────────

    def _infer(self, bgr_img: np.ndarray, face: FaceBBox) -> tuple[float, float]:
        """Dispatch to the active model. Returns (score, threshold)."""
        if self._mode == "cdcn" and self._cdcn_session is not None:
            return self._eval_cdcn(bgr_img, face), config.CDCN_THRESHOLD
        return self._eval_minifasnet_multiscale(bgr_img, face), config.MINIFASNET_THRESHOLD

    def _eval_minifasnet_multiscale(self, bgr_img: np.ndarray,
                                    face: FaceBBox) -> float:
        """
        Run MiniFASNetV2_SE at each scale defined in config.MINIFASNET_SCALES.
        Returns the weighted average of the real-class softmax outputs.

        This is the correct usage of the model. The v2 service was running
        the model on the entire 320×240 frame resized directly to 80×80
        without face detection or cropping — producing inputs completely
        outside the model's training distribution.
        """
        if self._fas_session is None:
            return 0.0

        input_name = self._fas_session.get_inputs()[0].name
        weighted_sum = 0.0

        for (size, margin), weight in zip(
                config.MINIFASNET_SCALES,
                config.MINIFASNET_SCALE_WEIGHTS):

            crop   = self._detector.crop_face(bgr_img, face,
                                              margin_factor=margin,
                                              target_size=size)
            tensor = self._preprocess_minifasnet(crop)
            output = self._fas_session.run(None, {input_name: tensor})[0][0]

            # output[1] = real-class softmax probability
            real_prob   = float(np.clip(output[1], 0.0, 1.0))
            weighted_sum += real_prob * weight

        return weighted_sum

    def _eval_cdcn(self, bgr_img: np.ndarray, face: FaceBBox) -> float:
        """
        CDCN++ inference on a 256×256 aligned face crop.
        The model outputs a depth map (B×1×H×W) where:
          Real face  → depth values near 1.0 (bright)
          Spoof      → depth values near 0.0 (dark)
        Mean of the depth map is used as the liveness score.
        """
        crop = self._detector.crop_face(bgr_img, face,
                                        margin_factor=1.3,
                                        target_size=256)
        rgb  = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0

        # ImageNet normalisation (CDCN was trained with ImageNet-pretrained backbone)
        mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
        std  = np.array([0.229, 0.224, 0.225], dtype=np.float32)
        norm = (rgb - mean) / std

        tensor     = np.expand_dims(norm.transpose(2, 0, 1), axis=0).astype(np.float32)
        input_name = self._cdcn_session.get_inputs()[0].name
        depth_map  = self._cdcn_session.run(None, {input_name: tensor})[0]   # (1,1,H,W)

        return float(np.mean(depth_map))

    # ─── Preprocessing ────────────────────────────────────────────────────────

    @staticmethod
    def _preprocess_minifasnet(bgr_crop: np.ndarray) -> np.ndarray:
        """
        Convert an 80×80 BGR face crop to the MiniFASNet ONNX input tensor.
        Shape: (1, 3, 80, 80), dtype float32, range [0, 1].
        """
        rgb = cv2.cvtColor(bgr_crop, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        chw = rgb.transpose(2, 0, 1)                        # HWC → CHW
        return np.expand_dims(chw, axis=0)                  # add batch dim
