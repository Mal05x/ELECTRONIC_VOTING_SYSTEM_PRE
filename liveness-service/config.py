"""
config.py — Centralised configuration for the MFA E-Voting Liveness Service.
All tuning constants live here. Edit once; every module picks them up.

Model selection:
  LIVENESS_MODEL=minifasnet_multiscale  (default, no extra download required)
  LIVENESS_MODEL=cdcn                   (better; requires running cdcn_export.py first)
"""
import os

# ─── Model paths ─────────────────────────────────────────────────────────────
# Absolute path anchored to this file's own directory.
# CRITICAL on Render: the process CWD is the repo root (/opt/render/project/src/)
# but models live in liveness-service/models/. A relative "models/" string
# resolves to the wrong location even after a successful build.
_HERE              = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR         = os.path.join(_HERE, "models")

# Two MiniFASNet models, each trained at a different crop scale.
# This is the genuine multi-scale approach from the Silent-Face paper:
# different models per scale, not one model run at arbitrary crops.
MINIFASNET_V2_PATH    = os.path.join(MODELS_DIR, "MiniFASNetV2.onnx")    # scale 2.7
MINIFASNET_V1SE_PATH  = os.path.join(MODELS_DIR, "MiniFASNetV1SE.onnx")  # scale 4.0

FACE_DETECTOR_PATH    = os.path.join(MODELS_DIR, "version-RFB-320.onnx")
CDCN_PATH             = os.path.join(MODELS_DIR, "CDCNpp.onnx")

# ─── Active liveness model ───────────────────────────────────────────────────
# "minifasnet_multiscale" — V2 (scale 2.7) + V1SE (scale 4.0) averaged
# "cdcn"                  — CDCN++ depth-map model (run cdcn_export.py first)
# Falls back to minifasnet_multiscale if CDCNpp.onnx is absent.
LIVENESS_MODEL = os.environ.get("LIVENESS_MODEL", "minifasnet_multiscale")

# ─── Decision thresholds ─────────────────────────────────────────────────────
MINIFASNET_THRESHOLD  = 0.75   # Weighted-average real-class softmax across both models
CDCN_THRESHOLD        = 0.55   # Mean depth-map pixel value (0 = spoof, 1 = real)
BURST_FUSED_THRESHOLD = 0.72   # Final liveness_weight x liveness + flow_weight x flow

# ─── Multi-scale MiniFASNet schedule ─────────────────────────────────────────
# Each entry: (model_path, crop_margin_factor, weight)
#
# margin_factor maps the paper scale to crop_face() margin:
#   paper scale 2.7  -> margin 1.35  (each side extends by 0.85x face width)
#   paper scale 4.0  -> margin 2.0   (each side extends by 1.5x face width)
MINIFASNET_SCALE_CONFIG = [
    (MINIFASNET_V2_PATH,   1.35, 0.5),   # MiniFASNetV2,   scale 2.7
    (MINIFASNET_V1SE_PATH, 2.0,  0.5),   # MiniFASNetV1SE, scale 4.0
]

# ─── Optical flow (burst mode) ────────────────────────────────────────────────
FLOW_MIN_MOTION  = 0.08
FLOW_MAX_MOTION  = 8.0
FLOW_WEIGHT      = 0.35
LIVENESS_WEIGHT  = 0.65

# ─── Face detector ───────────────────────────────────────────────────────────
FACE_CONFIDENCE_THRESHOLD = 0.70
FACE_MIN_SIZE_PX          = 48

# ─── Security ─────────────────────────────────────────────────────────────────
LIVENESS_SECRET = os.environ.get("LIVENESS_SECRET", "")
