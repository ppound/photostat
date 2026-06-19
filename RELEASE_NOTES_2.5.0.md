# PhotoStat 2.5.0 — Aesthetic Scoring

This release adds **local aesthetic / quality scoring** for your photos, plus
flexible result sorting and a tidier results toolbar. Score your collection with
a free, local AI model and use the score to surface your best shots — or to find
and cull the weakest.

## New: Aesthetic Scoring

- **Local & free quality scoring** — a new Dockerized backend (port 8003) runs
  the [IQA-PyTorch](https://github.com/chaofengc/IQA-PyTorch) no-reference model
  (default **CLIP-IQA+**) to score each photo for aesthetic quality. No API key,
  no per-image cost; uses your NVIDIA GPU automatically when available.
- **0–100 score in its own field** — scores are stored as `aesthetic_score`,
  **separate from your manual star rating**, which is never modified or written
  to sidecars.
- **Three ways to score** (all incremental — already-scored photos are skipped
  unless you force a rescore):
  - **Index → Score Photos (aesthetic)…** — bulk-score the whole library with a
    progress bar.
  - **Results AI ▾ → Score Selected (aesthetic)** — score the current selection.
  - **`--score-aesthetics`** CLI for scripted/background runs.
- **Use the score** — a **Score** column in results, an **Aesthetic score** sort
  option, a **Min Score** search filter, and an **Aesthetic Score** facet band.

See [docs/AESTHETIC_SCORING.md](docs/AESTHETIC_SCORING.md) for setup, workflows,
and metric options.

## New: Flexible Sorting

- Sort results by **Date taken**, **Aesthetic score**, or **Rating**, with a
  **direction toggle** (↓/↑). Photos missing the chosen field sort last.
- Sorting aesthetic score or rating **ascending** surfaces the weakest images
  first — a fast culling workflow: review, multi-select, and delete via
  **File ▾ → Delete**.

## Improved: Results Toolbar

- The results actions are now grouped into **AI ▾** (Analyze, Generate, Score
  Selected) and **File ▾** (Copy, Move, Rename, Upload, Re-index, Delete) menus,
  with **Slideshow** and the **Sort** controls kept visible. Re-index is now
  discoverable in the toolbar, not just the right-click menu.

## Under the Hood

- The new `aesthetic_score` field is added to the OpenSearch index via an
  **additive mapping update** — no reindex required, and existing metadata
  (including your ratings) is untouched.
- Docs: a dedicated aesthetic-scoring guide, expanded Docker docs covering how to
  start/stop individual backend services, and updated configuration/user-guide
  references.

## Test Coverage

- **115 tests passing.**

## Upgrading

Drop-in upgrade from 2.4.x. To use aesthetic scoring, start the new `aesthetic`
Docker backend (see [docker/README.md](docker/README.md)) and run one of the
scoring options above. Everything else works unchanged.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.4.1...v2.5.0
