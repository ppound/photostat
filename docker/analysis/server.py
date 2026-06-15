#!/usr/bin/env python3
"""
PhotoStat Analysis HTTP service (Moondream).

A thin FastAPI wrapper around photostat_moondream.py so the Java app can run the
local Moondream vision model inside a container over HTTP instead of spawning a
local Python worker. The image is sent as base64 bytes, so the container needs
no access to the host filesystem.

Endpoints:
    GET  /health  - readiness + device (cpu/cuda/mps)
    POST /analyze - caption/answer one image (single prompt or several)
"""

import base64
import io
from typing import List, Optional

from fastapi import FastAPI
from PIL import Image
from pydantic import BaseModel

import photostat_moondream as pm

app = FastAPI(title="PhotoStat Analysis (Moondream)")

_model = None
_device = None


def get_model():
    """Lazily load the Moondream model (takes 10-30s; loads once, then cached)."""
    global _model, _device
    if _model is None:
        _device = pm.detect_device()
        _model = pm.load_model(_device)
    return _model


class AnalyzeRequest(BaseModel):
    image: str                          # base64-encoded image bytes
    prompt: Optional[str] = None        # single question
    prompts: Optional[List[str]] = None # or several, encoded once for speed


@app.get("/health")
def health():
    info = {
        "status": "ok",
        "device": pm.detect_device(),
        "model_loaded": _model is not None,
    }
    try:
        import transformers
        info["transformers_version"] = transformers.__version__
    except Exception:
        pass
    return info


@app.post("/analyze")
def analyze(req: AnalyzeRequest):
    model = get_model()
    raw = base64.b64decode(req.image)
    image = Image.open(io.BytesIO(raw)).convert("RGB")
    encoded = model.encode_image(image)

    if req.prompts:
        responses = [model.query(encoded, p)["answer"] for p in req.prompts]
        return {"status": "ok", "responses": responses}

    response_text = model.query(encoded, req.prompt or "")["answer"]
    return {"status": "ok", "response": response_text}
