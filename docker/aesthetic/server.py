#!/usr/bin/env python3
"""
PhotoStat Aesthetic HTTP service.

A thin FastAPI wrapper around photostat_aesthetic.py so the Java app can score
images for quality/aesthetics against a containerized IQA model over HTTP
instead of spawning a local Python worker. Images are sent as base64 bytes, so
the container needs no access to the host filesystem.

The metric defaults to CLIP-IQA+ and can be overridden with the
PHOTOSTAT_IQA_METRIC environment variable (e.g. nima, musiq, topiq_nr, maniqa).

Endpoints:
    GET  /health      - readiness + device (cpu/cuda/mps) + metric
    POST /score       - score one base64 image
    POST /score-batch - score many base64 images (best throughput)
"""

import base64
import io
import os
from typing import List

from fastapi import FastAPI
from PIL import Image
from pydantic import BaseModel

import photostat_aesthetic as pa

app = FastAPI(title="PhotoStat Aesthetic")

_metric = None
_device = None
_metric_name = os.environ.get("PHOTOSTAT_IQA_METRIC", pa.DEFAULT_METRIC)


def get_metric():
    """Lazily load the IQA metric (downloads weights on first use, then cached)."""
    global _metric, _device
    if _metric is None:
        _device = pa.detect_device()
        _metric = pa.load_metric(_metric_name, _device)
    return _metric


class ImageItem(BaseModel):
    id: str          # logical path/id used for result labeling
    data: str        # base64-encoded image bytes


class ScoreRequest(BaseModel):
    image: str       # base64-encoded image bytes


class ScoreBatchRequest(BaseModel):
    images: List[ImageItem]


@app.on_event("startup")
def _warm_up():
    # Pre-load the model so the first real request isn't slow. Best-effort:
    # on first run this downloads the metric weights, and we don't want a
    # transient failure here to make /health unreachable.
    try:
        get_metric()
    except Exception as e:
        print(f"Startup model load failed (will retry on first request): {e}")


@app.get("/health")
def health():
    info = {
        "status": "ok",
        "device": pa.detect_device(),
        "metric": _metric_name,
        "model_loaded": _metric is not None,
    }
    try:
        import pyiqa
        info["pyiqa_version"] = pyiqa.__version__
    except Exception:
        pass
    return info


def _decode(data: str) -> Image.Image:
    return Image.open(io.BytesIO(base64.b64decode(data))).convert("RGB")


@app.post("/score")
def score(req: ScoreRequest):
    metric = get_metric()
    result = pa.score_image(metric, _metric_name, _decode(req.image), _device)
    return {"status": "ok", "metric": _metric_name, **result}


@app.post("/score-batch")
def score_batch(req: ScoreBatchRequest):
    metric = get_metric()
    results = []
    for item in req.images:
        try:
            r = pa.score_image(metric, _metric_name, _decode(item.data), _device)
            results.append({"id": item.id, **r})
        except Exception as e:
            # Mirror the other backends: skip a bad image, keep the batch going.
            print(f"Error scoring {item.id}: {e}")
            results.append({"id": item.id, "error": str(e)})
    return {"status": "ok", "metric": _metric_name, "results": results}
