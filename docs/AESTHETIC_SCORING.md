# Aesthetic Scoring

PhotoStat can score your photos for **aesthetic / perceptual quality** using a
local, free image-quality model — useful for surfacing your best shots and for
finding low-quality images to cull. Scoring runs entirely on your machine via a
Docker backend; no API key and no cloud upload.

## Table of Contents

- [Overview](#overview)
- [How it works](#how-it-works)
- [Starting the backend](#starting-the-backend)
- [Scoring your photos](#scoring-your-photos)
  - [Bulk: Index → Score Photos](#bulk-index--score-photos)
  - [Selection: AI → Score Selected](#selection-ai--score-selected)
  - [Command line](#command-line)
- [Using scores](#using-scores)
- [Aesthetic score vs. rating](#aesthetic-score-vs-rating)
- [Choosing a metric](#choosing-a-metric)
- [Troubleshooting](#troubleshooting)

## Overview

- **Local & free** — runs the [IQA-PyTorch](https://github.com/chaofengc/IQA-PyTorch)
  no-reference quality model (default **CLIP-IQA+**) in a Docker container. No
  API key, no per-image cost.
- **0–100 score** — every scored photo gets an `aesthetic_score` you can sort,
  filter, and facet on. Higher is better. (Stored internally as 0–1; shown as
  0–100 everywhere in the UI.)
- **Separate from your ratings** — the AI score lives in its own field and
  **never touches your manual star rating**. See
  [Aesthetic score vs. rating](#aesthetic-score-vs-rating).
- **GPU-accelerated** — uses your NVIDIA GPU automatically when available;
  scoring a large library takes minutes, not hours.

## How it works

The score is produced by the **`aesthetic`** Docker backend (port 8003), one of
PhotoStat's optional containerized services. PhotoStat sends each image to the
container as resized JPEG bytes (so the container needs no access to your photo
files), the model returns a quality score, and PhotoStat writes it to the
`aesthetic_score` field in OpenSearch. Scores are recomputable at any time and
are **not** written to sidecar files.

Adding the field is non-destructive: it's a new field added to the existing
index with no reindex required, so running the scorer never affects your
existing metadata.

## Starting the backend

Aesthetic scoring is **Docker-only** (there's no local-Python mode). Start the
service from the `docker/` directory:

```bash
# CPU
docker compose up -d opensearch aesthetic

# GPU (NVIDIA) — include both compose files
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d opensearch aesthetic

# Confirm it's up and which device it loaded on
curl localhost:8003/health   # expect {"status":"ok","device":"cuda"|"cpu",...}
```

On first start the model weights download into a named volume (cached for next
time). See [docker/README.md](../docker/README.md) for full Docker setup,
GPU requirements, and how to start/stop individual services.

In **Settings → AI Analysis → Aesthetic Scoring**, the endpoint defaults to
`http://localhost:8003`; use **Test Connection** to verify it's reachable.

## Scoring your photos

There are three ways to score, all writing the same `aesthetic_score` field.
Scoring is **incremental** — already-scored photos are skipped unless you ask to
rescore — so it's safe to re-run after indexing more photos.

### Bulk: Index → Score Photos

The main path for a whole library. In the **Index** tab, click
**"Score Photos (aesthetic)…"**. Confirm (optionally tick **Rescore all** to
re-score everything), and a background pass scores every indexed photo with a
progress bar.

### Selection: AI → Score Selected

For an ad-hoc subset. In the search results, select one or more images, then use
the toolbar **AI ▾ → Score Selected (aesthetic)**. Selected images are always
(re)scored, and the Score column and detail panel update in place.

### Command line

```bash
# Score all indexed photos (incremental)
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics

# Preview without scoring
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics --dry-run

# Re-score everything, larger batches
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics --force --batch 32

# Only a subdirectory
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics --dir /path/to/photos
```

| Option | Description |
|--------|-------------|
| `--dir <path>` | Only score indexed images whose path starts with `<path>` |
| `--batch <n>` | Images per backend call (1–256, default 16) |
| `--force` | Re-score images that already have a score |
| `--dry-run` | Show what would be done without scoring |
| `--quiet`, `-q` | Minimal output |
| `--no-progress` | Disable progress updates |

## Using scores

Once photos are scored, the score is exposed throughout the Search tab:

- **Score column** — a 0–100 value in the results table (blank if unscored).
- **Sort** — the results **Sort** dropdown includes **Aesthetic score**; use the
  direction toggle (↓/↑) to put your best photos first, or lowest first.
- **Filter** — the search panel's **Min Score** control (0–100) restricts results
  to photos at or above a threshold.
- **Facet** — the **Aesthetic Score** facet groups results into bands
  (90–100, 80–90, …); click a band to filter.
- **Detail panel** — a read-only **Aesthetic** value next to the rating field.

### Culling workflow

To find and remove weak images: set **Sort → Aesthetic score** with the
direction toggle on **ascending (↑)** so the lowest-scored photos come first
(unscored photos sort to the very end). Review, multi-select the ones to remove,
and use **File ▾ → Delete**.

## Aesthetic score vs. rating

These are two **independent** fields:

| | Rating | Aesthetic score |
|---|---|---|
| Source | You (manual stars, 1–5) | AI model (0–100) |
| Field | `rating` | `aesthetic_score` |
| Editable | Yes (and written to sidecars / XMP) | No (read-only, recomputable) |
| Purpose | Your own judgement | A consistent automated quality signal |

The scorer **never** writes to your `rating`, and it is not saved to sidecar
files. This lets you do things like "show photos the AI scored highly that I
haven't rated yet," and keeps your hand-assigned stars authoritative.

## Choosing a metric

The default metric is **CLIP-IQA+**, which correlates well with human "is this a
good photo" judgments. You can switch metrics by setting the
`PHOTOSTAT_IQA_METRIC` environment variable on the `aesthetic` service and
restarting it:

| Metric | Notes |
|--------|-------|
| `clipiqa+` | Default. General aesthetic quality. |
| `nima` | Trained on the AVA aesthetics dataset. |
| `musiq` | Multi-scale image quality. |
| `topiq_nr` | Modern no-reference quality. |
| `maniqa` | Attention-based no-reference quality. |

Whatever metric is configured, the score is normalized to 0–100, so sorting and
filtering work the same. If you change metrics, re-score with **Rescore all** /
`--force` so all photos use the new metric (the metric used is recorded per
photo in `aesthetic_metric`).

## Troubleshooting

**"Aesthetic backend not reachable" / Test Connection fails**
The `aesthetic` container isn't running or the endpoint is wrong. Start it
(`docker compose … up -d aesthetic`), confirm with `curl localhost:8003/health`,
and check the endpoint in Settings.

**Score column is blank for some photos**
Those photos haven't been scored yet (scoring is incremental), or they're in a
format the backend can't decode. Run **Score Photos** / `--score-aesthetics`, or
**AI → Score Selected** on them.

**Scoring is slow**
The backend is running on CPU. Start it with the GPU compose override (see
[Starting the backend](#starting-the-backend)) and confirm `"device":"cuda"` at
`/health`.

**Sorting by aesthetic puts blanks at the top/bottom**
Unscored photos always sort **last**, in both directions — that's expected.
Score them first for a complete ranking.
