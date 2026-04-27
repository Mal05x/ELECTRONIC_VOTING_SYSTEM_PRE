"""
config.py — Centralised configuration for the MFA E-Voting Liveness Service.
All tuning constants live here. Edit once; every module picks them up.

Model selection:
  LIVENESS_MODEL=minifasnet_multiscale  (default, no extra download required)
  LIVENESS_MODEL=cdcn                   (better; requires running cdcn_export.py first)
"""
import os

# ─── Model paths ─────────────────────────────────────────────────────────────
MODELS_DIR         = "models"
MINIFASNET_PATH    = f"{MODELS_DIR}/MiniFASNetV2_SE.onnx"
FACE_DETECTOR_PATH = f"{MODELS_DIR}/version-RFB-320.onnx"   # Ultraface RFB-320
CDCN_PATH          = f"{MODELS_DIR}/CDCNpp.onnx"

# ─── Active liveness model ───────────────────────────────────────────────────
# "minifasnet_multiscale" — three-scale MiniFASNetV2 (immediate, no extra model)
# "cdcn"                  — CDCN++ depth-map model  (run cdcn_export.py first)
# If "cdcn" is selected but CDCNpp.onnx is absent, falls back to minifasnet_multiscale.
LIVENESS_MODEL = os.environ.get("LIVENESS_MODEL", "minifasnet_multiscale")

# ─── Decision thresholds ─────────────────────────────────────────────────────
# Per-model thresholds — real_score must EXCEED these to pass.
MINIFASNET_THRESHOLD = 0.75   # Weighted-average real-class softmax across 3 scales
CDCN_THRESHOLD       = 0.55   # Mean depth-map pixel value (0 = spoof, 1 = real)

# Burst fusion thresholds
BURST_FUSED_THRESHOLD = 0.72  # Final liveness_weight×liveness + flow_weight×flow

# ─── Multi-scale MiniFASNet crop schedule ────────────────────────────────────
# Each tuple: (output_size_px, face_bbox_margin_factor)
# Margin 1.0 → tight crop; 1.5 → 50% context; 2.0 → 100% extra context.
# This is the multi-scale inference described in the original
# Silent-Face-Anti-Spoofing paper — NOT currently used in the broken v2 pipeline.
MINIFASNET_SCALES         = [(80, 1.0), (80, 1.5), (80, 2.0)]
MINIFASNET_SCALE_WEIGHTS  = [1/3, 1/3, 1/3]   # Equal weighting

# ─── Optical flow (burst mode) ────────────────────────────────────────────────
FLOW_MIN_MOTION  = 0.08  # Below this  → suspiciously static (printed photo)
FLOW_MAX_MOTION  = 8.0   # Above this  → excessive blur/shake → penalise
FLOW_WEIGHT      = 0.35  # Contribution of flow score to final burst decision
LIVENESS_WEIGHT  = 0.65  # Contribution of model score to final burst decision

# ─── Face detector ───────────────────────────────────────────────────────────
FACE_CONFIDENCE_THRESHOLD = 0.70   # Min face detection confidence (0–1)
FACE_MIN_SIZE_PX          = 48     # Ignore faces smaller than this in either dimension

# ─── Security ─────────────────────────────────────────────────────────────────
LIVENESS_SECRET = os.environ.get("LIVENESS_SECRET", "")
