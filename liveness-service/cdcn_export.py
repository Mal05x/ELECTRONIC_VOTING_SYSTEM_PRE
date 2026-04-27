"""
cdcn_export.py — CDCN++ PyTorch architecture + ONNX export
============================================================
Implements the CDCN++ architecture from:
  "Searching Central Difference Convolutional Networks for Face Anti-Spoofing"
  Zitong Yu, Yunxiao Qin, Xiaqing Xu, Chenxu Zhao, Zhen Lei, Guoying Zhao
  CVPR 2020 — https://github.com/ZitongYu/CDCN

Usage (requires PyTorch — not needed for production inference):
  pip install torch torchvision
  python cdcn_export.py

Pre-trained weights:
  Option A — Paper authors provide weights for OULU-NPU / SiW / MSU datasets.
    Request via: https://github.com/ZitongYu/CDCN (check Issues/Releases)
  Option B — Train your own on a face anti-spoofing dataset.
    Minimum recommended: CASIA-FASD, REPLAY-ATTACK, or OULU-NPU Protocol 1.
    Training script not included here — refer to the original repo.

  Place the downloaded .pth checkpoint at:
    models/CDCNpp_pretrained.pth
  The export script handles both DataParallel-wrapped and bare state dicts.

Activation:
  After export, set in liveness-service/.env:
    LIVENESS_MODEL=cdcn
"""

import os
import sys
from pathlib import Path

# ── Guard against running without PyTorch ────────────────────────────────────
try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    import numpy as np
except ImportError:
    print("ERROR: PyTorch is required for model export only.")
    print("Install with: pip install torch torchvision")
    print("PyTorch is NOT required to run the liveness service once CDCNpp.onnx exists.")
    sys.exit(1)


# ─── Central Difference Convolution (CDC) ───────────────────────────────────

class CDC(nn.Module):
    """
    Central Difference Convolution.

    Output = θ × standard_conv(x) + (1−θ) × central_diff_conv(x)

    The central difference term computes per-pixel gradient information:
    each position is the difference between the pixel and its neighbourhood
    centre. This makes the layer inherently sensitive to texture boundaries
    and spoof artefacts (printing grain, screen moiré, etc.).

    θ=0.7 is the value recommended in the original CDCN paper (ablation §4.2).
    Lower θ → more standard convolution; θ=1.0 → pure CDC.
    """
    def __init__(self, in_c: int, out_c: int,
                 kernel_size: int = 3, stride: int = 1, padding: int = 1,
                 groups: int = 1, bias: bool = False, theta: float = 0.7):
        super().__init__()
        self.conv  = nn.Conv2d(in_c, out_c, kernel_size,
                               stride=stride, padding=padding,
                               groups=groups, bias=bias)
        self.theta = theta

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        standard = self.conv(x)
        if self.theta == 0:
            return standard

        # Central difference: kernel_diff collapses spatial dims of the weight tensor
        # to produce a 1×1 kernel per output channel — it measures the sum of the
        # kernel (i.e., the "DC" component), which becomes the central difference term.
        kernel_diff = self.conv.weight.sum(dim=(2, 3), keepdim=True)
        diff        = F.conv2d(x, kernel_diff,
                               stride=self.conv.stride,
                               padding=0,
                               groups=self.conv.groups)
        return standard - self.theta * diff


# ─── CDCN++ Building Blocks ───────────────────────────────────────────────────

class CDCBlock(nn.Module):
    """BN → CDC3×3 → ReLU residual block."""
    def __init__(self, in_c: int, out_c: int, theta: float = 0.7):
        super().__init__()
        self.bn   = nn.BatchNorm2d(in_c)
        self.cdc  = CDC(in_c, out_c, 3, 1, 1, theta=theta)
        self.relu = nn.ReLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.relu(self.cdc(self.bn(x)))


class SpatialAttention(nn.Module):
    """
    Multi-scale spatial attention module (CDCN++ extension, Eq. 5 in paper).
    Produces a channel-collapsed attention map that re-weights feature maps
    to focus on discriminative face regions (eye, nose, lip boundaries).
    """
    def __init__(self, channels: int):
        super().__init__()
        self.conv    = nn.Conv2d(channels, 1, kernel_size=1, bias=False)
        self.sigmoid = nn.Sigmoid()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        attn = self.sigmoid(self.conv(x))  # (B,1,H,W) in [0,1]
        return x * attn                    # channel-wise scale


# ─── CDCN++ Full Architecture ─────────────────────────────────────────────────

class CDCNpp(nn.Module):
    """
    CDCN++ — Central Difference Convolutional Network Plus Plus.

    Input:  (B, 3, 256, 256)   RGB image, ImageNet-normalised
    Output: (B, 1, 32, 32)     Depth map proxy
                                 Real face  → values near 1.0
                                 Spoof      → values near 0.0

    mean(depth_map) is used as the liveness score in liveness_evaluator.py.
    The depth map head produces spatially-resolved output for potential
    audit visualisation (bright regions = face, dark = spoof artefacts).
    """

    def __init__(self, theta: float = 0.7):
        super().__init__()

        # ── Stage 1 — low-level CDC feature extraction (256→128) ─────────────
        self.stem = nn.Sequential(
            CDC(3,  64, 3, 1, 1, theta=theta), nn.BatchNorm2d(64),  nn.ReLU(True),
            CDC(64, 64, 3, 1, 1, theta=theta), nn.BatchNorm2d(64),  nn.ReLU(True),
            CDC(64, 64, 3, 1, 1, theta=theta), nn.BatchNorm2d(64),  nn.ReLU(True),
        )
        self.pool1 = nn.MaxPool2d(3, stride=2, padding=1)   # → 128×128

        # ── Stage 2 — mid-level features with spatial attention (128→64) ─────
        self.stage2 = nn.Sequential(
            CDCBlock(64,  128, theta),
            CDCBlock(128, 196, theta),
            CDCBlock(196, 128, theta),
        )
        self.attn2 = SpatialAttention(128)
        self.pool2 = nn.MaxPool2d(3, stride=2, padding=1)   # → 64×64

        # ── Stage 3 — high-level features with spatial attention (64→32) ─────
        self.stage3 = nn.Sequential(
            CDCBlock(128, 128, theta),
            CDCBlock(128, 196, theta),
            CDCBlock(196, 128, theta),
        )
        self.attn3 = SpatialAttention(128)
        self.pool3 = nn.MaxPool2d(3, stride=2, padding=1)   # → 32×32

        # ── Depth map head ────────────────────────────────────────────────────
        self.head = nn.Sequential(
            CDCBlock(128, 128, theta),
            nn.Conv2d(128, 1, kernel_size=1, bias=False),
            nn.Sigmoid()                      # constrain output to [0, 1]
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.pool1(self.stem(x))            # → (B, 64,  128, 128)
        x = self.pool2(self.attn2(self.stage2(x)))  # → (B, 128,  64,  64)
        x = self.pool3(self.attn3(self.stage3(x)))  # → (B, 128,  32,  32)
        return self.head(x)                     # → (B,   1,  32,  32)


# ─── Export ───────────────────────────────────────────────────────────────────

def export():
    models_dir   = Path("models")
    models_dir.mkdir(exist_ok=True)
    weights_path = models_dir / "CDCNpp_pretrained.pth"
    onnx_path    = models_dir / "CDCNpp.onnx"

    print("Building CDCN++ model...")
    model = CDCNpp(theta=0.7)
    model.eval()

    if weights_path.exists():
        print(f"  Loading pre-trained weights from {weights_path}...")
        state = torch.load(weights_path, map_location="cpu")

        # Some checkpoints wrap the model in DataParallel — strip the prefix
        state = {k.replace("module.", ""): v for k, v in state.items()}

        missing, unexpected = model.load_state_dict(state, strict=False)
        if missing:
            print(f"  ⚠  Missing keys ({len(missing)}): {missing[:3]} ...")
        if unexpected:
            print(f"  ⚠  Unexpected keys ({len(unexpected)}): {unexpected[:3]} ...")
        if not missing and not unexpected:
            print("  ✓ All weights loaded successfully.")
    else:
        print(f"  ⚠  No pre-trained weights at {weights_path}")
        print("     Exporting architecture ONLY — not usable for inference.")
        print("     Obtain weights from: https://github.com/ZitongYu/CDCN")
        print("     Then re-run this script.")

    dummy_input = torch.randn(1, 3, 256, 256)

    print(f"  Exporting to {onnx_path}...")
    torch.onnx.export(
        model,
        dummy_input,
        str(onnx_path),
        opset_version=12,
        input_names=["input"],
        output_names=["depth_map"],
        dynamic_axes={"input": {0: "batch"}, "depth_map": {0: "batch"}},
        do_constant_folding=True,
    )

    size_kb = onnx_path.stat().st_size // 1024
    print(f"  ✓ Exported: {onnx_path}  ({size_kb} KB)")
    print()
    print("To activate CDCN++ in the liveness service, add to .env:")
    print("  LIVENESS_MODEL=cdcn")


if __name__ == "__main__":
    print("CDCN++ → ONNX Export Script")
    print("=" * 45)
    export()
