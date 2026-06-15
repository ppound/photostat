#!/usr/bin/env python3
"""
PhotoStat Faces HTTP service.

A thin FastAPI wrapper around photostat_faces.py so the Java app can run face
detection and clustering against a containerized InsightFace model over HTTP
instead of spawning a local Python worker. Images are sent as base64 bytes, so
the container needs no access to the host filesystem.

Endpoints:
    GET  /health             - readiness + which providers (CPU/CUDA) loaded
    POST /faces/detect-batch - detect faces in a batch of base64 images
    POST /faces/cluster      - cluster a list of face embeddings
"""

import base64
from typing import List

import cv2
import numpy as np
from fastapi import FastAPI
from pydantic import BaseModel

import photostat_faces as pf

app = FastAPI(title="PhotoStat Faces")

_face_app = None


def get_app():
    """Lazily initialize the InsightFace app (model loads once, then cached)."""
    global _face_app
    if _face_app is None:
        _face_app = pf.get_face_app()
    return _face_app


class ImageItem(BaseModel):
    id: str          # logical path/id used for face_id hashing and result labeling
    data: str        # base64-encoded image bytes


class DetectBatchRequest(BaseModel):
    images: List[ImageItem]
    threshold: float = 0.6


class ClusterRequest(BaseModel):
    faces: List[dict]
    threshold: float = 0.6


@app.on_event("startup")
def _warm_up():
    # Pre-load the model so the first real request isn't slow. Best-effort:
    # on first run this downloads buffalo_l, and we don't want a transient
    # failure here to make /health unreachable.
    try:
        get_app()
    except Exception as e:
        print(f"Startup model load failed (will retry on first request): {e}")


@app.get("/health")
def health():
    providers = []
    try:
        fa = get_app()
        providers = fa.det_model.session.get_providers()
    except Exception:
        try:
            import onnxruntime
            providers = onnxruntime.get_available_providers()
        except Exception:
            pass

    info = {
        "status": "ok",
        "providers": providers,
        "gpu_available": "CUDAExecutionProvider" in providers,
    }
    try:
        import insightface
        import onnxruntime
        info["insightface_version"] = insightface.__version__
        info["onnxruntime_version"] = onnxruntime.__version__
    except Exception:
        pass
    return info


def _decode_image(data: str):
    raw = base64.b64decode(data)
    arr = np.frombuffer(raw, np.uint8)
    return cv2.imdecode(arr, cv2.IMREAD_COLOR)


@app.post("/faces/detect-batch")
def detect_batch(req: DetectBatchRequest):
    fa = get_app()
    faces = []
    for item in req.images:
        img = _decode_image(item.data)
        try:
            faces.extend(pf.detect_faces_in_array(fa, img, item.id, req.threshold))
        except Exception as e:
            # Mirror the worker: skip a bad image, keep processing the batch.
            print(f"Error processing {item.id}: {e}")
    return {"status": "ok", "faces": faces}


@app.post("/faces/cluster")
def cluster(req: ClusterRequest):
    clusters, method = pf.cluster_faces(req.faces, req.threshold)
    return {"status": "ok", "clusters": clusters, "method": method}
