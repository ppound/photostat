# PhotoStat 2.6.0 — Prebuilt Docker Backends

This release makes the optional AI backends (face recognition, local analysis,
and aesthetic scoring) **install-free**. You no longer need to clone the
repository or build images by hand — PhotoStat pulls prebuilt images and sets up
the Docker Compose files for you.

## New: Prebuilt Docker images (no clone required)

- **Published to GitHub Container Registry** — the `faces`, `analysis`, and
  `aesthetic` services are built by CI in both **CPU** and **GPU** variants and
  pushed to `ghcr.io/ppound/photostat-*`. Docker pulls them on demand; no local
  build step.
- **Compose files ship with the app** — on startup PhotoStat deploys
  `docker-compose.yml` and `docker-compose.gpu.yml` to `~/.photostat/`, the same
  place as your config. Start the backends with:
  ```bash
  docker compose -f ~/.photostat/docker-compose.yml up -d
  ```
- **Your edits are preserved** — a pristine `*.dist.yml` reference copy is
  refreshed on every launch, but your live `docker-compose.yml` is only written
  if it's missing, so GPU toggles, `PHOTOSTAT_IQA_METRIC`, and port changes stay
  intact. Delete your copy and relaunch to reset to defaults.
- **Power users keep the source path** — the `build:`-based Compose files under
  `docker/` remain for anyone who prefers to build the images locally or hack on
  the Python.

## Under the Hood

- New **Publish Docker images** GitHub Actions workflow: a matrix build over the
  three services × {CPU, GPU}, tagged `:cpu` / `:gpu` plus immutable
  `:<version>-cpu` / `:<version>-gpu` tags, with layer caching.
- `ConfigService` now extracts the bundled Compose resources into `~/.photostat`
  as part of first-run setup.

## Test Coverage

- **115 tests passing.**

## Upgrading

Drop-in upgrade from 2.5.x. To use the Docker backends, run
`docker compose -f ~/.photostat/docker-compose.yml up -d` (add
`-f ~/.photostat/docker-compose.gpu.yml` for NVIDIA GPUs). See
[docker/README.md](docker/README.md) for per-service control and GPU notes.
Existing local-Python setups continue to work unchanged.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.5.0...v2.6.0
