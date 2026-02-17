#!/usr/bin/env python3
"""
PhotoStat Face Detection & Clustering via InsightFace.

Commands:
    check                           - Verify dependencies, print version JSON
    detect <image_path> [threshold] - Detect faces in a single image
    detect-batch <input_json> <output_json> [threshold] - Batch detection
    cluster <face_data_json> <output_json> [threshold]  - Cluster faces
"""

import json
import sys
import os
import numpy as np


def check():
    """Verify insightface + onnxruntime + sklearn are installed."""
    try:
        import insightface
        import onnxruntime
        result = {
            "status": "ok",
            "insightface_version": insightface.__version__,
            "onnxruntime_version": onnxruntime.__version__
        }

        # Check available execution providers
        available_providers = onnxruntime.get_available_providers()
        result["available_providers"] = available_providers
        result["gpu_available"] = "CUDAExecutionProvider" in available_providers

        # If GPU available, try to get device info
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
    """Initialize InsightFace app with buffalo_l model."""
    import insightface
    app = insightface.app.FaceAnalysis(
        name="buffalo_l",
        root=os.path.expanduser("~/.photostat/faces/models"),
        providers=["CUDAExecutionProvider", "CPUExecutionProvider"]
    )
    app.prepare(ctx_id=0, det_size=(640, 640))
    return app


def detect_faces_in_image(app, image_path, threshold=0.6):
    """Detect faces in a single image using a pre-initialized app."""
    import cv2

    img = cv2.imread(image_path)
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

    # Filter faces that have embeddings
    faces_with_emb = [f for f in faces if f.get("embedding") and len(f["embedding"]) > 0]

    if not faces_with_emb:
        with open(output_path, "w") as f:
            json.dump([], f)
        print(json.dumps({"status": "ok", "clusters": 0, "method": "none"}))
        return

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

    with open(output_path, "w") as f:
        json.dump(clusters, f, indent=2)

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
    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
