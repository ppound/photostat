# CPU image for the PhotoStat analysis service (Moondream).
# Build context is the repo root (see docker-compose.yml).
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models

WORKDIR /app

COPY docker/analysis/requirements-cpu.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY src/main/resources/photostat_moondream.py .
COPY docker/analysis/server.py .

# HuggingFace cache (moondream2 weights download here on first run).
VOLUME ["/models"]
EXPOSE 8002

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8002"]
