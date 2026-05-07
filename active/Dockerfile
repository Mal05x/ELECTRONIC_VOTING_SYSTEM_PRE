FROM python:3.11-slim

RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .

# FIX BUG-2: port 5002 (5001 = passive MiniFASNet service)
EXPOSE 5002

# FIX BUG-4: use gunicorn (production WSGI), not Flask dev server
# 2 workers is enough for low-concurrency edge hardware
CMD ["gunicorn", "-w", "2", "-b", "0.0.0.0:5002", "--timeout", "30", "app:app"]
