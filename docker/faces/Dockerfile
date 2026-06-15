# CPU image for the PhotoStat faces service.
# Build context is the repo root (see docker-compose.yml) so we can copy the
# shared Python source from src/main/resources.
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PHOTOSTAT_FACES_MODEL_ROOT=/models

# build-essential + cmake: insightface compiles native extensions on install.
# libglib2.0-0 + libgl1: OpenCV runtime shared libraries.
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY docker/faces/requirements-cpu.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY src/main/resources/photostat_faces.py .
COPY docker/faces/server.py .

# Model cache (buffalo_l downloads here on first run); mount a volume to persist.
VOLUME ["/models"]
EXPOSE 8001

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001"]
