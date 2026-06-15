#!/usr/bin/env python3
"""
PhotoStat Face Detection & Clustering via InsightFace.

Commands:
    check                           - Verify dependencies, print version JSON
    detect <image_path> [threshold] - Detect faces in a single image
    detect-batch <input_json> <output_json> [threshold] - Batch detection
    cluster <face_data_json> <output_json> [threshold]  - Cluster faces
    worker                          - Persistent worker: reads JSON commands from stdin
"""

import json
import sys
import os
import numpy as np


def _test_cuda_provider():
    """Test if onnxruntime-gpu can use the CUDA provider.

    Returns (True, None) if GPU inference should work.
    Returns (False, human_readable_error) if it will not.

    We use get_available_providers() rather than ctypes because on Windows,
    Python 3.8+ activates safe DLL search mode (SetDefaultDllDirectories) which
    removes PATH from DLL lookup. ctypes therefore cannot find CUDA DLLs even when
    they are in PATH. onnxruntime works around this by calling AddDllDirectory
    internally before loading its providers — get_available_providers() exercises
    that same code path, making it the only reliable test without running inference.
    """
    try:
        import onnxruntime as ort
    except ImportError:
        return False, "onnxruntime not installed"

    if sys.platform == "darwin":
        return False, "CUDA not supported on macOS"

    ort_dir = os.path.dirname(ort.__file__)

    # Check onnxruntime-gpu is installed — the CPU package lacks the CUDA provider DLL.
    # This also catches the "both onnxruntime and onnxruntime-gpu installed" conflict
    # where the GPU package files exist but the Python namespace is corrupted.
    if sys.platform == "win32":
        cuda_provider_dll = os.path.join(ort_dir, "capi", "onnxruntime_providers_cuda.dll")
    else:
        cuda_provider_dll = os.path.join(ort_dir, "capi", "libonnxruntime_providers_cuda.so")

    if not os.path.exists(cuda_provider_dll):
        return False, "onnxruntime-gpu not installed (run: pip uninstall onnxruntime && pip install onnxruntime-gpu)"

    # Ask onnxruntime which providers it can actually load using its own DLL loader.
    # If CUDAExecutionProvider appears, onnxruntime successfully loaded the provider —
    # the same result we get during real inference.
    try:
        available = ort.get_available_providers()
    except Exception as e:
        return False, f"Failed to query onnxruntime providers: {e}"

    if "CUDAExecutionProvider" in available:
        return True, None

    return False, (
        "CUDA provider not available. Possible causes: "
        "(1) NVIDIA GPU driver not installed, "
        "(2) CUDA Toolkit 12.x not installed (CUDA 13.x is not compatible — "
        "install CUDA 12.6 from developer.nvidia.com/cuda-12-6-0-download-archive), "
        "(3) both onnxruntime and onnxruntime-gpu are installed "
        "(fix: pip uninstall onnxruntime onnxruntime-gpu -y && pip install onnxruntime-gpu)."
    )


def check():
    """Verify insightface + onnxruntime are installed, and test actual GPU inference capability."""
    try:
        import insightface
        import onnxruntime
        result = {
            "status": "ok",
            "insightface_version": insightface.__version__,
            "onnxruntime_version": onnxruntime.__version__
        }

        # Check declared providers
        available_providers = onnxruntime.get_available_providers()
        result["available_providers"] = available_providers

        # Only mark gpu_available=true if the CUDA provider library actually loads.
        # get_available_providers() returning CUDA just means the driver is visible —
        # it does NOT confirm that cuBLAS/cuDNN runtime DLLs are present.
        if "CUDAExecutionProvider" in available_providers:
            gpu_ok, gpu_error = _test_cuda_provider()
            result["gpu_available"] = gpu_ok
            if gpu_error:
                result["gpu_error"] = gpu_error
        else:
            result["gpu_available"] = False

        if result["gpu_available"]:
            try:
                import torch
                result["gpu_name"] = torch.cuda.get_device_name(0)
            except Exception:
                result["gpu_name"] = "CUDA device detected"

        # sklearn is optional but recommended for clustering
        try:
            import sklearn
            result["sklearn_version"] = sklearn.__version__
        except ImportError:
            result["sklearn_version"] = None
        print(json.dumps(result))
    except ImportError as e:
        result = {"status": "error", "message": str(e)}
        print(json.dumps(result))
        sys.exit(1)


def get_face_app():
    """Initialize InsightFace app with buffalo_l model.

    The model root can be overridden with the PHOTOSTAT_FACES_MODEL_ROOT env var
    (used by the Docker image to cache models in a mounted volume); it defaults to
    the local ~/.photostat path so non-Docker usage is unchanged.
    """
    import insightface
    model_root = os.environ.get(
        "PHOTOSTAT_FACES_MODEL_ROOT",
        os.path.expanduser("~/.photostat/faces/models"),
    )
    app = insightface.app.FaceAnalysis(
        name="buffalo_l",
        root=model_root,
        providers=["CUDAExecutionProvider", "CPUExecutionProvider"]
    )
    app.prepare(ctx_id=0, det_size=(640, 640))
    return app


def detect_faces_in_image(app, image_path, threshold=0.6):
    """Detect faces in a single image file using a pre-initialized app."""
    import cv2

    img = cv2.imread(image_path)
    return detect_faces_in_array(app, img, image_path, threshold)


def detect_faces_in_array(app, img, image_path, threshold=0.6):
    """Detect faces in an already-decoded BGR image array (as from cv2).

    image_path is used only as a label for face_id hashing and the returned
    record, so callers working with in-memory image bytes (e.g. the HTTP server)
    can pass a logical path/id while supplying the decoded pixels in img.
    """
    if img is None:
        return []

    faces = app.get(img)
    results = []

    for face in faces:
        score = float(face.det_score)
        if score < threshold:
            continue

        bbox = face.bbox.astype(int)
        x, y, x2, y2 = int(bbox[0]), int(bbox[1]), int(bbox[2]), int(bbox[3])
        w, h = x2 - x, y2 - y

        # Clamp to image bounds
        img_h, img_w = img.shape[:2]
        x = max(0, x)
        y = max(0, y)
        w = min(w, img_w - x)
        h = min(h, img_h - y)

        embedding = face.embedding.tolist() if face.embedding is not None else []
        face_id = hex(hash(f"{image_path}|{x}|{y}|{w}|{h}") & 0xFFFFFFFF)[2:]

        results.append({
            "face_id": face_id,
            "image_path": image_path,
            "x": x,
            "y": y,
            "width": w,
            "height": h,
            "confidence": round(score, 4),
            "embedding": embedding
        })

    return results


def cmd_detect(args):
    """Handle 'detect' command."""
    if len(args) < 1:
        print("Usage: detect <image_path> [threshold]", file=sys.stderr)
        sys.exit(1)

    image_path = args[0]
    threshold = float(args[1]) if len(args) > 1 else 0.6

    app = get_face_app()
    results = detect_faces_in_image(app, image_path, threshold)
    print(json.dumps(results, indent=2))


def cmd_detect_batch(args):
    """Handle 'detect-batch' command.

    Processes images in the input JSON list, writes detected faces to the output JSON.
    Reports progress on stderr as PROGRESS:current/total.
    If output file already exists with partial results, appends to it (resume support).
    """
    if len(args) < 2:
        print("Usage: detect-batch <input_json> <output_json> [threshold]", file=sys.stderr)
        sys.exit(1)

    input_path = args[0]
    output_path = args[1]
    threshold = float(args[2]) if len(args) > 2 else 0.6

    with open(input_path, "r") as f:
        image_paths = json.load(f)

    # Load existing results if output file exists (resume support)
    existing_faces = []
    already_processed = set()
    if os.path.exists(output_path):
        try:
            with open(output_path, "r") as f:
                existing_faces = json.load(f)
                already_processed = {f["image_path"] for f in existing_faces}
        except (json.JSONDecodeError, KeyError):
            existing_faces = []

    # Filter out already-processed images
    remaining = [p for p in image_paths if p not in already_processed]
    total = len(image_paths)
    skipped = len(image_paths) - len(remaining)

    if skipped > 0:
        print(f"Resuming: {skipped} already processed, {len(remaining)} remaining", file=sys.stderr, flush=True)

    all_faces = list(existing_faces)

    # Initialize model once for the batch
    import cv2
    app = get_face_app()

    for i, image_path in enumerate(remaining):
        print(f"PROGRESS:{skipped + i + 1}/{total}", file=sys.stderr, flush=True)

        try:
            faces = detect_faces_in_image(app, image_path, threshold)
            all_faces.extend(faces)
        except Exception as e:
            print(f"Error processing {image_path}: {e}", file=sys.stderr)

        # Save checkpoint every 100 images for crash safety
        if (i + 1) % 100 == 0:
            with open(output_path, "w") as f:
                json.dump(all_faces, f)

    # Final save
    with open(output_path, "w") as f:
        json.dump(all_faces, f)

    new_faces = len(all_faces) - len(existing_faces)
    print(json.dumps({
        "status": "ok",
        "faces_found": new_faces,
        "total_faces": len(all_faces),
        "images_processed": len(remaining),
        "images_skipped": skipped
    }))


def cmd_worker():
    """Persistent worker mode: reads JSON commands from stdin, writes JSON responses to stdout.

    Loads the InsightFace model once, then processes detect-batch commands indefinitely
    until a shutdown command is received or stdin is closed.
    """
    import cv2
    app = get_face_app()

    # Report which providers the detection model actually loaded on (GPU vs CPU)
    providers = []
    try:
        providers = app.det_model.session.get_providers()
    except Exception:
        try:
            import onnxruntime
            providers = onnxruntime.get_available_providers()
        except Exception:
            pass
    print(json.dumps({"status": "ready", "providers": providers}), flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            print(json.dumps({"status": "error", "message": str(e)}), flush=True)
            continue

        command = request.get("command")
        if command == "detect-batch":
            paths = request.get("paths", [])
            threshold = float(request.get("threshold", 0.6))
            faces = []
            total = len(paths)
            for i, image_path in enumerate(paths):
                print(f"PROGRESS:{i+1}/{total}", file=sys.stderr, flush=True)
                try:
                    faces.extend(detect_faces_in_image(app, image_path, threshold))
                except Exception as e:
                    print(f"Error processing {image_path}: {e}", file=sys.stderr, flush=True)
            print(json.dumps({"status": "ok", "faces": faces}), flush=True)
        elif command == "shutdown":
            break
        else:
            print(json.dumps({"status": "error", "message": f"unknown command: {command}"}), flush=True)


def cluster_with_sklearn(embeddings_norm, threshold):
    """Cluster using sklearn DBSCAN — O(N log N), memory-efficient."""
    from sklearn.cluster import DBSCAN

    # DBSCAN uses distance, not similarity. cosine_distance = 1 - cosine_similarity.
    # eps = 1 - threshold (e.g., threshold 0.6 → eps 0.4)
    eps = 1.0 - threshold

    clustering = DBSCAN(eps=eps, min_samples=1, metric="cosine", n_jobs=-1)
    labels = clustering.fit_predict(embeddings_norm)
    return labels.tolist()


def cluster_fallback(embeddings_norm, threshold):
    """Fallback clustering without sklearn — uses Chinese Whispers-style approach.

    O(N * K) where K is average neighbors, much better than O(N^2) full matrix.
    Processes faces in random order, assigning each to the best matching existing
    cluster or creating a new one.
    """
    n = len(embeddings_norm)
    labels = [-1] * n

    # Cluster centroids (running average of normalized embeddings)
    centroids = []
    cluster_sizes = []
    next_label = 0

    # Process in random order for better clustering
    indices = list(range(n))
    np.random.shuffle(indices)

    for idx in indices:
        emb = embeddings_norm[idx]

        if not centroids:
            # First face — create first cluster
            labels[idx] = next_label
            centroids.append(emb.copy())
            cluster_sizes.append(1)
            next_label += 1
            continue

        # Compare against all existing centroids
        centroid_matrix = np.array(centroids)
        similarities = centroid_matrix @ emb

        best_cluster = int(np.argmax(similarities))
        best_sim = similarities[best_cluster]

        if best_sim >= threshold:
            # Assign to existing cluster and update centroid
            labels[idx] = best_cluster
            cluster_sizes[best_cluster] += 1
            # Running average
            n_c = cluster_sizes[best_cluster]
            centroids[best_cluster] = (centroids[best_cluster] * (n_c - 1) + emb) / n_c
            # Re-normalize
            norm = np.linalg.norm(centroids[best_cluster])
            if norm > 0:
                centroids[best_cluster] /= norm
        else:
            # New cluster
            labels[idx] = next_label
            centroids.append(emb.copy())
            cluster_sizes.append(1)
            next_label += 1

    return labels


def cluster_faces(faces, threshold=0.6):
    """Cluster a list of face dicts into person clusters.

    Each face dict needs "face_id", "confidence" and "embedding" keys.
    Returns (clusters, method) where method is one of "dbscan", "fallback", "none".
    Pure (no file I/O) so it can be reused by both the CLI and the HTTP server.
    """
    # Filter faces that have embeddings
    faces_with_emb = [f for f in faces if f.get("embedding") and len(f["embedding"]) > 0]

    if not faces_with_emb:
        return [], "none"

    n = len(faces_with_emb)
    print(f"Clustering {n} faces with threshold {threshold}...", file=sys.stderr, flush=True)

    # Build and normalize embedding matrix
    embeddings = np.array([f["embedding"] for f in faces_with_emb])
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    norms[norms == 0] = 1
    embeddings_norm = embeddings / norms

    # Try sklearn DBSCAN first, fall back to centroid method
    method = "fallback"
    try:
        labels = cluster_with_sklearn(embeddings_norm, threshold)
        method = "dbscan"
    except ImportError:
        print("sklearn not available, using centroid-based clustering", file=sys.stderr, flush=True)
        labels = cluster_fallback(embeddings_norm, threshold)

    # Group by cluster label
    cluster_map = {}
    for idx, label in enumerate(labels):
        # DBSCAN uses -1 for noise — treat each noise point as its own cluster
        if label == -1:
            label = -(idx + 1)  # unique negative label
        if label not in cluster_map:
            cluster_map[label] = []
        cluster_map[label].append(idx)

    # Build cluster objects
    clusters = []
    for cluster_idx, (label, face_indices) in enumerate(sorted(cluster_map.items(), key=lambda x: -len(x[1]))):
        face_ids = [faces_with_emb[i]["face_id"] for i in face_indices]

        # Pick representative as the face with highest confidence
        best_idx = max(face_indices, key=lambda i: faces_with_emb[i]["confidence"])

        clusters.append({
            "cluster_id": str(cluster_idx),
            "person_name": None,
            "face_ids": face_ids,
            "representative_face_id": faces_with_emb[best_idx]["face_id"]
        })

    return clusters, method


def cmd_cluster(args):
    """Handle 'cluster' command — uses DBSCAN if sklearn available, else centroid-based fallback."""
    if len(args) < 2:
        print("Usage: cluster <face_data_json> <output_json> [threshold]", file=sys.stderr)
        sys.exit(1)

    face_data_path = args[0]
    output_path = args[1]
    threshold = float(args[2]) if len(args) > 2 else 0.6

    with open(face_data_path, "r") as f:
        faces = json.load(f)

    clusters, method = cluster_faces(faces, threshold)

    with open(output_path, "w") as f:
        json.dump(clusters, f, indent=2)

    if method == "none":
        print(json.dumps({"status": "ok", "clusters": 0, "method": "none"}))
    else:
        n = sum(1 for f in faces if f.get("embedding") and len(f["embedding"]) > 0)
        print(json.dumps({"status": "ok", "clusters": len(clusters), "faces": n, "method": method}))


def main():
    if len(sys.argv) < 2:
        print("Usage: photostat_faces.py <command> [args...]", file=sys.stderr)
        print("Commands: check, detect, detect-batch, cluster", file=sys.stderr)
        sys.exit(1)

    command = sys.argv[1]
    args = sys.argv[2:]

    if command == "check":
        check()
    elif command == "detect":
        cmd_detect(args)
    elif command == "detect-batch":
        cmd_detect_batch(args)
    elif command == "cluster":
        cmd_cluster(args)
    elif command == "worker":
        cmd_worker()
    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
