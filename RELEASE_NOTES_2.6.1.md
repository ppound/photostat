# PhotoStat 2.6.1 — Faces GPU Status & Faster Name Saves

A patch release fixing the Faces panel's GPU/CPU status readout with the Docker backend and making "Save Name" skip images that already carry the name.

## Fixes

### Faces panel showed "Available (CPU)" even when running on GPU

The Faces panel matched the health response literal `"gpu_available": true` (with a space), which only the local Python worker's pretty-printed JSON produces. The Docker FastAPI backend emits compact JSON (`"gpu_available":true`), so the panel always reported "Available (CPU)" even when the container was running on CUDA. Whitespace is now stripped before matching — mirroring the CLI's handling — so the panel correctly shows "Available (GPU)" for the Docker backend. The GPU was in use all along; only the status label was wrong.

## Improvements

### "Save Name" no longer re-writes images that already have the name

Assigning a name to a face cluster re-wrote every image's OpenSearch document and sidecar file, even for images that already carried that name. Re-saving a mostly-named cluster therefore did near-full work for a handful of genuinely new faces. Since each document is already fetched, its `persons` list is now checked and only images missing the name are written. A log line reports the split (e.g. `assignName 'Alice': 3 of 812 images updated (809 already named)`).

## Test Coverage

- **115 tests passing.**

## Upgrading

Drop-in upgrade from 2.6.0. No re-indexing or re-detection needed.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.6.0...v2.6.1
