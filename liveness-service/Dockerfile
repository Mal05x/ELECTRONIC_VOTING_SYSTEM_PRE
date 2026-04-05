# ── Liveness Detection Service ─────────────────────────────────────────────
# Binds to 0.0.0.0 inside the container so the host can access it on 5001
# ──────────────────────────────────────────────────────────────────────────
FROM python:3.11-slim-bookworm

WORKDIR /app

# We bypassed apt-get entirely by relying on opencv-python-headless!

# 1. Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 2. Copy the application code
COPY main.py .

# 3. Copy the AI model you already downloaded via curl
COPY models/ ./models/

# Expose the port
EXPOSE 5001

# Run the FastAPI server.
# MUST be 0.0.0.0 inside Docker so Spring Boot can reach it.
CMD ["uvicorn", "main:app", \
     "--host", "0.0.0.0", \
     "--port", "5001", \
     "--workers", "2", \
     "--log-level", "info"]