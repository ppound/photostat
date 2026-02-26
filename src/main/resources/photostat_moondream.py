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
    device = "cpu"
    try:
        import torch
        if torch.cuda.is_available():
            device = "cuda"
        elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            device = "mps"
    except Exception:
        pass

    print(json.dumps({
        "status": "ok",
        "device": device,
        "transformers_version": transformers.__version__
    }))
    sys.exit(0)


def worker():
    """Persistent worker mode: load model once, process commands from stdin."""
    import torch
    from transformers import AutoModelForCausalLM
    from PIL import Image

    # Detect device
    device = "cpu"
    try:
        if torch.cuda.is_available():
            device = "cuda"
        elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            device = "mps"
    except Exception:
        pass

    # Load model (this takes 10-30+ seconds)
    print(f"Loading Moondream model on {device}...", file=sys.stderr, flush=True)

    model = None
    # Try GPU-optimized loading first, fall back to CPU
    load_attempts = []
    if device == "cuda":
        load_attempts.append(("cuda/bfloat16", {"torch_dtype": torch.bfloat16, "device_map": {"": "cuda"}}))
    elif device == "mps":
        load_attempts.append(("mps/float16", {"torch_dtype": torch.float16, "device_map": {"": "mps"}}))
        load_attempts.append(("mps/float32", {"torch_dtype": torch.float32, "device_map": {"": "mps"}}))
    # Always have CPU fallback — explicit device_map ensures it doesn't auto-detect MPS/CUDA
    load_attempts.append(("cpu/float32", {"device_map": {"": "cpu"}}))

    for desc, kwargs in load_attempts:
        try:
            print(f"Trying {desc}...", file=sys.stderr, flush=True)
            model = AutoModelForCausalLM.from_pretrained(
                "vikhyatk/moondream2",
                trust_remote_code=True,
                **kwargs,
            )
            if device == "cpu" and not kwargs:
                # No device_map specified, model is already on CPU
                pass
            print(f"Model loaded successfully ({desc})", file=sys.stderr, flush=True)
            break
        except Exception as e:
            print(f"Failed with {desc}: {e}", file=sys.stderr, flush=True)
            model = None

    if model is None:
        print(json.dumps({"status": "error", "message": "Failed to load model with all strategies"}),
              flush=True)
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
