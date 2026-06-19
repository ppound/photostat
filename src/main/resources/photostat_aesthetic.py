#!/usr/bin/env python3
"""
PhotoStat aesthetic / image-quality scoring.

Shared module (used by the Docker ``aesthetic`` service, and any future local
worker) that wraps a no-reference image-quality model from IQA-PyTorch
(``pyiqa``). Given a PIL image it returns a raw quality score plus a 0..1
normalized value suitable for ranking a whole library and mapping onto a star
rating.

The default metric is CLIP-IQA+, which correlates well with human "is this a
good photo" judgments and needs no reference image. Other pyiqa no-reference
metrics (nima, musiq, topiq_nr, maniqa) can be selected by name.
"""

# Each metric reports on a different scale; (lo, hi, higher_is_better) lets us
# normalize every score to a comparable 0..1 where 1.0 is best. Unknown metrics
# fall back to a 0..1 pass-through.
METRIC_RANGES = {
    "clipiqa":  (0.0, 1.0, True),
    "clipiqa+": (0.0, 1.0, True),
    "nima":     (1.0, 10.0, True),
    "musiq":    (0.0, 100.0, True),
    "topiq_nr": (0.0, 1.0, True),
    "maniqa":   (0.0, 1.0, True),
}

DEFAULT_METRIC = "clipiqa+"


def detect_device():
    """Return the best available torch device: 'cuda', 'mps', or 'cpu'."""
    device = "cpu"
    try:
        import torch
        if torch.cuda.is_available():
            device = "cuda"
        elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            device = "mps"
    except Exception:
        pass
    return device


def load_metric(name: str, device: str):
    """Create a pyiqa no-reference metric on the given device.

    Weights download once on first use and are cached under TORCH_HOME/HF_HOME
    (set to /models in the container so they survive restarts).
    """
    import pyiqa
    metric = pyiqa.create_metric(name, device=device)
    metric.eval()
    return metric


def normalize(name: str, score: float) -> float:
    """Map a raw metric score onto 0..1 where 1.0 is best, clamped to range."""
    lo, hi, higher_is_better = METRIC_RANGES.get(name, (0.0, 1.0, True))
    n = (score - lo) / (hi - lo) if hi > lo else score
    n = max(0.0, min(1.0, n))
    return n if higher_is_better else 1.0 - n


def score_image(metric, name: str, image, device: str) -> dict:
    """Score one PIL image. Returns {'score': raw, 'normalized': 0..1}."""
    import torch
    import torchvision.transforms.functional as TF
    tensor = TF.to_tensor(image.convert("RGB")).unsqueeze(0).to(device)  # NCHW, [0,1]
    with torch.no_grad():
        raw = float(metric(tensor).item())
    return {"score": raw, "normalized": normalize(name, raw)}
