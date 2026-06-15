#!/usr/bin/env python3
"""
PhotoStat Moondream Integration - Local AI image analysis using Moondream vision-language model.

Uses HuggingFace Transformers for truly local inference (no API key, no server).

Two modes:
  check  - Verify dependencies are installed, report device
  worker - Load model once, then loop reading JSON commands from stdin

Dependencies: pip install "transformers>=4.51,<5" torch Pillow accelerate
"""

import sys
import json


def check():
    """Verify dependencies and report device info."""
    errors = []

    if sys.version_info < (3, 10):
        print(json.dumps({
            "status": "error",
            "message": f"Python 3.10+ required (found {sys.version.split()[0]}). "
                       f"Moondream2's model code uses type union syntax (X | Y) that requires Python 3.10+. "
                       f"Upgrade Python or use a virtualenv with Python 3.10+."
        }))
        sys.exit(1)

    try:
        from PIL import Image
    except ImportError:
        errors.append("Pillow")

    try:
        import torch
    except ImportError:
        errors.append("torch")

    try:
        import transformers
    except ImportError:
        errors.append("transformers")

    try:
        import accelerate
    except ImportError:
        errors.append("accelerate")

    if errors:
        print(json.dumps({
            "status": "error",
            "message": f"Missing: {', '.join(errors)}. Install with: pip install \"transformers>=4.51,<5\" torch Pillow accelerate"
        }))
        sys.exit(1)

    # Check transformers version — moondream2 is incompatible with transformers 5.x
    import transformers
    tf_version = tuple(int(x) for x in transformers.__version__.split(".")[:2])
    if tf_version >= (5, 0):
        print(json.dumps({
            "status": "error",
            "message": f"transformers {transformers.__version__} is not compatible with moondream2. "
                       f"Install a compatible version: pip install \"transformers>=4.51,<5\""
        }))
        sys.exit(1)

    # Check for GPU availability
    device = detect_device()

    print(json.dumps({
        "status": "ok",
        "device": device,
        "transformers_version": transformers.__version__
    }))
    sys.exit(0)


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


def load_model(device):
    """Load the Moondream model for the given device, with CPU fallback.

    Returns the loaded model. Raises RuntimeError if every load attempt fails.
    Shared by the stdin worker and the HTTP server.
    """
    import torch
    from transformers import AutoModelForCausalLM

    # Load model (this takes 10-30+ seconds)
    print(f"Loading Moondream model on {device}...", file=sys.stderr, flush=True)

    # Try GPU-optimized loading first, fall back to CPU.
    # Note: moondream2 uses trust_remote_code with custom __init__ that doesn't support
    # device_map for MPS/CPU — load without device_map then move with .to() instead.
    load_attempts = []
    if device == "cuda":
        load_attempts.append(("cuda/bfloat16", {"torch_dtype": torch.bfloat16, "device_map": {"": "cuda"}}, None))
    elif device == "mps":
        load_attempts.append(("mps/float16", {"torch_dtype": torch.float16}, "mps"))
        load_attempts.append(("mps/float32", {}, "mps"))
    # Always have CPU fallback — load plain, no device_map
    load_attempts.append(("cpu/float32", {}, None))

    failures = []
    for desc, kwargs, move_to in load_attempts:
        try:
            print(f"Trying {desc}...", file=sys.stderr, flush=True)
            model = AutoModelForCausalLM.from_pretrained(
                "vikhyatk/moondream2",
                trust_remote_code=True,
                **kwargs,
            )
            if move_to:
                model = model.to(move_to)
            print(f"Model loaded successfully ({desc})", file=sys.stderr, flush=True)
            return model
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            print(f"Failed with {desc}: {tb}", file=sys.stderr, flush=True)
            failures.append(f"{desc}: {type(e).__name__}: {e}")

    raise RuntimeError("Failed to load model. Attempts: " + " | ".join(failures))


def worker():
    """Persistent worker mode: load model once, process commands from stdin."""
    from PIL import Image

    device = detect_device()
    try:
        model = load_model(device)
    except RuntimeError as e:
        print(json.dumps({"status": "error", "message": str(e)}), flush=True)
        sys.exit(1)

    # Signal ready on stdout
    print(json.dumps({"status": "ready", "device": device}), flush=True)

    # Command loop — read JSON from stdin, write JSON to stdout
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            print(json.dumps({"status": "error", "message": f"Invalid JSON: {e}"}), flush=True)
            continue

        command = request.get("command", "")

        if command == "shutdown":
            break
        elif command == "analyze":
            image_path = request.get("image_path", "")
            prompt = request.get("prompt", "")

            if not image_path:
                print(json.dumps({"status": "error", "message": "Missing image_path"}), flush=True)
                continue

            try:
                image = Image.open(image_path).convert("RGB")
                encoded = model.encode_image(image)
                response_text = model.query(encoded, prompt)["answer"]
                print(json.dumps({"status": "ok", "response": response_text}), flush=True)
            except Exception as e:
                print(json.dumps({"status": "error", "message": str(e)}), flush=True)
        elif command == "analyze_multi":
            # Encode image once, answer multiple questions — much faster than separate calls
            image_path = request.get("image_path", "")
            prompts = request.get("prompts", [])

            if not image_path:
                print(json.dumps({"status": "error", "message": "Missing image_path"}), flush=True)
                continue
            if not prompts:
                print(json.dumps({"status": "error", "message": "Missing prompts"}), flush=True)
                continue

            try:
                image = Image.open(image_path).convert("RGB")
                encoded = model.encode_image(image)
                responses = []
                for prompt in prompts:
                    answer = model.query(encoded, prompt)["answer"]
                    responses.append(answer)
                print(json.dumps({"status": "ok", "responses": responses}), flush=True)
            except Exception as e:
                print(json.dumps({"status": "error", "message": str(e)}), flush=True)
        else:
            print(json.dumps({"status": "error", "message": f"Unknown command: {command}"}), flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: photostat_moondream.py <check|worker>", file=sys.stderr)
        sys.exit(1)

    mode = sys.argv[1]
    if mode == "check":
        check()
    elif mode == "worker":
        worker()
    else:
        print(f"Unknown mode: {mode}", file=sys.stderr)
        sys.exit(1)
