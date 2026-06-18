# PhotoStat Docker backends

These images package PhotoStat's heavier dependencies so users don't have to
install Python, InsightFace, or PyTorch by hand. The JavaFX app stays on the
host and talks to the containers over HTTP.

| Service     | Port | Purpose                                              |
|-------------|------|------------------------------------------------------|
| `opensearch`| 9200 | Search index (same engine PhotoStat already uses)    |
| `faces`     | 8001 | Face detection + clustering (InsightFace/onnxruntime)|
| `analysis`  | 8002 | Local image tagging/captioning (Moondream)           |
| `aesthetic` | 8003 | Local image-quality / aesthetic scoring (IQA-PyTorch)|

The `faces`, `analysis`, and `aesthetic` images import the **same** Python
sources PhotoStat uses for local mode (`src/main/resources/photostat_faces.py`,
`photostat_moondream.py`, and `photostat_aesthetic.py`) — there is no forked
copy to keep in sync. The HTTP
servers (`docker/*/server.py`) are thin wrappers around those modules. Images
are sent as base64 bytes, so the containers need **no access to your photo
files** on disk.

## Quick start (CPU)

```bash
cd docker
docker compose up -d
```

First start downloads the models (InsightFace `buffalo_l`, Moondream2, and the
IQA metric weights). They are cached in named volumes (`faces-models`,
`hf-cache`, `iqa-models`) so later starts are fast.

Check readiness:

```bash
curl localhost:8001/health   # faces     -> providers / gpu_available
curl localhost:8002/health   # analysis  -> device (cpu/cuda/mps)
curl localhost:8003/health   # aesthetic -> device (cpu/cuda/mps) + metric
curl localhost:9200          # opensearch
```

## GPU (NVIDIA, optional)

CUDA speeds up face recognition substantially. Requirements:

- NVIDIA GPU + recent driver
- [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)
  (on Windows this works through WSL2)

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d
```

GPU is opt-in per service. To run, say, faces on GPU but analysis on CPU, only
include the override for the one service (or edit `docker-compose.gpu.yml`).

Confirm the GPU is in use:

```bash
curl localhost:8001/health   # expect "CUDAExecutionProvider" in providers
curl localhost:8002/health   # expect "device": "cuda"
```

> The GPU image pins (CUDA 12.6/12.4, onnxruntime-gpu 1.19.2, torch 2.5.1 cu124)
> are verified working on an NVIDIA RTX 4060 (driver 576.02, CUDA 12.9) under
> Docker Desktop on WSL2. Other drivers may need a different CUDA base image or
> `requirements-gpu.txt` pin — adjust the tags if your driver reports a CUDA
> initialization error (e.g. `CUDA failure 500: named symbol not found`, which
> usually means the host driver/runtime predates the image's CUDA version).

## Connecting PhotoStat

In **Settings**, point the face-recognition and analysis features at the Docker
endpoints (`http://localhost:8001` and `http://localhost:8002`) instead of local
Python. Use the **Test connection** button to confirm the device each service
loaded on. (Wiring on the Java side is added in a later step.)

## HTTP API

### faces — `POST /faces/detect-batch`
```json
{ "threshold": 0.6,
  "images": [ { "id": "/path/or/id.jpg", "data": "<base64 image>" } ] }
```
Returns `{ "status": "ok", "faces": [ { face_id, image_path, x, y, width,
height, confidence, embedding } ] }`.

### faces — `POST /faces/cluster`
```json
{ "threshold": 0.6,
  "faces": [ { "face_id": "...", "confidence": 0.99, "embedding": [ ... ] } ] }
```
Returns `{ "status": "ok", "clusters": [ ... ], "method": "dbscan|fallback|none" }`.

### analysis — `POST /analyze`
```json
{ "image": "<base64 image>", "prompt": "Describe this image" }
```
or batch several prompts (image encoded once):
```json
{ "image": "<base64 image>", "prompts": [ "Caption?", "Tags?" ] }
```
Returns `{ "status": "ok", "response": "..." }` or `{ "responses": [ ... ] }`.

### aesthetic — `POST /score`
```json
{ "image": "<base64 image>" }
```
Returns `{ "status": "ok", "metric": "clipiqa+", "score": 0.73, "normalized": 0.73 }`.

### aesthetic — `POST /score-batch`
```json
{ "images": [ { "id": "/path/or/id.jpg", "data": "<base64 image>" } ] }
```
Returns `{ "status": "ok", "metric": "clipiqa+", "results": [ { id, score,
normalized } ] }`. `normalized` is 0..1 (1.0 = best) regardless of which metric
is configured, so it maps cleanly onto a rating.
