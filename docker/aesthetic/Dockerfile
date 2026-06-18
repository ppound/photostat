# CPU image for the PhotoStat aesthetic-scoring service (IQA-PyTorch).
# Build context is the repo root (see docker-compose.yml).
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models \
    TORCH_HOME=/models

WORKDIR /app

# pyiqa/torchvision need a few native libs (libGL, glib) for image decoding.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY docker/aesthetic/requirements-cpu.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY src/main/resources/photostat_aesthetic.py .
COPY docker/aesthetic/server.py .

# IQA model weights (CLIP-IQA+ etc.) download here on first run.
VOLUME ["/models"]
EXPOSE 8003

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8003"]
