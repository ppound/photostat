# PhotoStat 2.4.0 — Docker Backends, Gemini 2.5, Re-indexing

This release packages PhotoStat's heavy Python backends (face recognition and local Moondream analysis) as optional Docker services with GPU support, refreshes Gemini to the current 2.5 model family, and adds a way to re-index individual images from search results.

## What's New

### Dockerized Faces & Analysis Backends

The face-recognition (InsightFace) and local-analysis (Moondream) Python backends can now run as containerized HTTP services, so you no longer need a local Python environment to use them.

- **Two images, isolated dependencies** — faces (onnxruntime) and analysis (torch) ship separately so their CUDA stacks don't collide.
- **CPU and GPU variants.** `docker compose up -d` runs CPU; add the GPU override (`-f docker-compose.gpu.yml`) on an NVIDIA machine. GPU images are verified working on an RTX 4060 under Docker Desktop / WSL2.
- **Local or Docker, per feature.** Settings → Face Recognition and AI Analysis each have a backend selector (Local Python vs. Docker) and endpoint field, with Test/Check buttons. The JavaFX app stays on the host and talks to the containers over HTTP; images are sent inline, so no shared filesystem is required.
- See `docker/README.md` for setup.

### Gemini 2.5 Models

Google retired the Gemini 1.5 and 2.0 generations for image analysis, so the old model choices now return "model not found."

- The model dropdown now offers the **Gemini 2.5 family** (`gemini-2.5-flash`, `gemini-2.5-pro`, `gemini-2.5-flash-lite`) and is **editable**, so newer model ids can be entered without waiting for an app update.
- **Existing configs are auto-migrated** off the retired model names on startup, so analysis keeps working after upgrade with no manual change.

### Re-index Selected

Right-click images in the results table and choose **Re-index Selected** to re-extract EXIF/metadata from disk and overwrite the index documents — without re-indexing the whole collection. Useful when an image was indexed by an older build (or hit a transient extraction failure) and is missing metadata that's present in the file.

## Fixes

- **Face-detect CLI** no longer mislabels a GPU backend as `Execution: CPU`. The check now reads the backend's health response regardless of JSON spacing, so the Docker GPU service reports correctly.

## Documentation

- `docker/README.md`, `docs/FACE_RECOGNITION.md`, `docs/AI_ANALYSIS.md`, `docs/CONFIGURATION.md` — Docker backends, Gemini 2.5 models, and verified GPU configuration.

## Test Coverage

- **115 tests passing.**

## Upgrading

Drop-in upgrade. On first launch, a stale Gemini model name in your config is migrated to `gemini-2.5-flash` automatically. Docker backends are entirely opt-in — nothing changes if you keep using the local Python path.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.3.0...v2.4.0
