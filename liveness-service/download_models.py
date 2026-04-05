"""
download_models.py
==================
Bullet-proof downloader for the MiniFASNetV2-SE ONNX model.
Includes native `curl` fallbacks for Windows MinGW64/Git Bash environments.
"""

import urllib.request
import sys
import os
import subprocess
import ssl
from pathlib import Path

MODELS_DIR = Path(__file__).parent / "models"
MODELS_DIR.mkdir(exist_ok=True)

# Correct Case-Sensitive URL
MODEL_URL = "https://raw.githubusercontent.com/suriAI/face-antispoof-onnx/main/models/best_model.onnx"
DEST_FILE = MODELS_DIR / "MiniFASNetV2_SE.onnx"

def download_via_curl():
    """Fallback method using native curl to bypass Python SSL/DNS issues."""
    print("  [Fallback] Attempting download via native curl...")
    try:
        # curl -L follows redirects, -o specifies output file
        result = subprocess.run(
            ["curl", "-L", "-o", str(DEST_FILE), MODEL_URL],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        if result.returncode == 0 and DEST_FILE.exists() and DEST_FILE.stat().st_size > 1000000:
            print(f"  ✓ Success via curl! ({DEST_FILE.stat().st_size // 1024} KB)")
            return True
        else:
            return False
    except FileNotFoundError:
        print("  [Error] Curl is not installed on this system.")
        return False

def download_via_python():
    """Standard Python urllib method."""
    print(f"  Downloading {DEST_FILE.name} via Python...", end=" ", flush=True)
    try:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        req = urllib.request.Request(MODEL_URL, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, context=ctx) as response, open(DEST_FILE, 'wb') as out_file:
            out_file.write(response.read())

        actual_kb = DEST_FILE.stat().st_size // 1024
        if actual_kb < 1000:
            raise ValueError("Downloaded file is too small. Probably a 404 page.")

        print(f"done ({actual_kb} KB).")
        return True
    except Exception as e:
        print(f"FAILED: {e}")
        return False

def main():
    print("Voting Terminal Liveness Model Downloader (Robust Version)")
    print("=" * 60)

    if DEST_FILE.exists() and DEST_FILE.stat().st_size > 1000000:
        print(f"  ✓ Model already present ({DEST_FILE.stat().st_size // 1024} KB) — skipping.")
    else:
        # Try Python first
        success = download_via_python()

        # If Python fails (which it is doing on your Git Bash), force curl
        if not success:
            if DEST_FILE.exists():
                DEST_FILE.unlink() # clean up the corrupted file
            success = download_via_curl()

        if not success:
            print("\n[CRITICAL FAILURE] Both Python and Curl failed to download the model.")
            print("To fix this, paste this exact command into your terminal and press Enter:")
            print(f"\ncurl -L -o models/MiniFASNetV2_SE.onnx {MODEL_URL}\n")
            sys.exit(1)

    print("\nModel ready. Start the service with:")
    print("  uvicorn main:app --host 127.0.0.1 --port 5001")

if __name__ == "__main__":
    main()