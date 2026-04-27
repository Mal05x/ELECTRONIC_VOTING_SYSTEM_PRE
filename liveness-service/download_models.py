"""
download_models.py — Downloads all ONNX models required by the liveness service.

Models downloaded:
  1. MiniFASNetV2_SE.onnx    — face anti-spoofing (primary liveness model)
  2. version-RFB-320.onnx   — Ultraface RFB-320 face detector (NEW in V3)

CDCN++ is NOT downloaded here because it requires running cdcn_export.py
(which needs PyTorch) to produce CDCNpp.onnx from pre-trained weights.
See cdcn_export.py for instructions.
"""

import urllib.request
import subprocess
import ssl
import sys
from pathlib import Path

MODELS_DIR = Path(__file__).parent / "models"
MODELS_DIR.mkdir(exist_ok=True)

MODELS = [
    {
        "name": "MiniFASNetV2_SE.onnx",
        "url":  "https://raw.githubusercontent.com/suriAI/face-antispoof-onnx/main/models/best_model.onnx",
        "min_kb": 1000,
        "desc": "MiniFASNetV2_SE — face anti-spoofing model",
    },
    {
        "name": "version-RFB-320.onnx",
        "url":  (
            "https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB"
            "/raw/master/models/onnx/version-RFB-320.onnx"
        ),
        "min_kb": 300,
        "desc": "Ultraface RFB-320 — face detector optimised for 320×240 input",
    },
]


# ─── Downloader ──────────────────────────────────────────────────────────────

def _python_download(url: str, dest: Path) -> bool:
    print(f"  [Python] Downloading {dest.name}…", end=" ", flush=True)
    try:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode    = ssl.CERT_NONE
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, context=ctx) as r, open(dest, "wb") as f:
            f.write(r.read())
        print(f"done ({dest.stat().st_size // 1024} KB)")
        return True
    except Exception as e:
        print(f"FAILED — {e}")
        return False


def _curl_download(url: str, dest: Path) -> bool:
    print(f"  [curl]   Downloading {dest.name}…", end=" ", flush=True)
    try:
        r = subprocess.run(
            ["curl", "-L", "--silent", "--show-error", "-o", str(dest), url],
            capture_output=True
        )
        if r.returncode == 0 and dest.exists() and dest.stat().st_size > 1024:
            print(f"done ({dest.stat().st_size // 1024} KB)")
            return True
        print(f"FAILED — {r.stderr.decode().strip()}")
        return False
    except FileNotFoundError:
        print("FAILED — curl not found on PATH")
        return False


def _download(model: dict) -> bool:
    dest = MODELS_DIR / model["name"]

    if dest.exists() and dest.stat().st_size // 1024 >= model["min_kb"]:
        print(f"  ✓ {model['name']} already present "
              f"({dest.stat().st_size // 1024} KB) — skipping.")
        return True

    print(f"\n  ↓ {model['desc']}")

    # Try Python urllib first, then curl fallback
    for attempt in (_python_download, _curl_download):
        if dest.exists():
            dest.unlink()
        if attempt(model["url"], dest):
            if dest.stat().st_size // 1024 >= model["min_kb"]:
                return True
            print(f"  ⚠  File too small after download — possibly a 404 page.")
            dest.unlink()

    # Both failed — print manual command
    print(f"\n  [MANUAL] Run this in your terminal:")
    print(f"    curl -L -o models/{model['name']} \"{model['url']}\"\n")
    return False


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    print("MFA E-Voting Liveness Service — Model Downloader (V3)")
    print("=" * 58)

    failed = []
    for model in MODELS:
        ok = _download(model)
        if not ok:
            failed.append(model["name"])

    print()
    if failed:
        print(f"[ERROR] {len(failed)} model(s) failed to download: {', '.join(failed)}")
        print("The service will not start until all models are present.")
        print("Try the manual curl commands printed above, then re-run this script.")
        sys.exit(1)

    print("All models ready.")
    print()
    print("Start the liveness service:")
    print("  uvicorn main:app --host 127.0.0.1 --port 5001")
    print()
    print("To use CDCN++ (optional upgrade):")
    print("  1. pip install torch torchvision")
    print("  2. Place CDCNpp_pretrained.pth in models/")
    print("     (https://github.com/ZitongYu/CDCN)")
    print("  3. python cdcn_export.py")
    print("  4. Add LIVENESS_MODEL=cdcn to .env")


if __name__ == "__main__":
    main()
