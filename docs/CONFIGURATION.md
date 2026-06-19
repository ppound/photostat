# Configuration Reference

PhotoStat stores its configuration in `~/.photostat/config.json`. This document describes all available settings.

## Configuration File Location

| Platform | Location |
|----------|----------|
| Windows | `C:\Users\<username>\.photostat\config.json` |
| macOS | `/Users/<username>/.photostat/config.json` |
| Linux | `/home/<username>/.photostat/config.json` |

---

## Example Configuration

```json
{
  "opensearch": {
    "host": "localhost",
    "port": 9200,
    "ssl": false,
    "index_name": "photostat",
    "username": "",
    "password": ""
  },
  "indexing": {
    "directories": [
      "/home/user/Pictures",
      "/media/photos"
    ],
    "batch_size": 50,
    "file_extensions": [".jpg", ".jpeg", ".png", ".cr2", ".nef", ".arw", ".dng", ".raf"]
  },
  "ui": {
    "thumbnail_size": 200,
    "results_per_page": 50
  },
  "exiftool": {
    "path": "exiftool",
    "use_for_raw": true
  },
  "sidecar": {
    "format": "json",
    "read_both": true
  },
  "logging": {
    "enabled": false,
    "level": "INFO",
    "max_log_size_mb": 5,
    "max_log_files": 3
  },
  "cache": {
    "enabled": true,
    "max_size_mb": 500
  },
  "faces": {
    "python_path": "python3",
    "enabled": true,
    "confidence_threshold": 0.6,
    "cluster_threshold": 0.6,
    "mode": "local",
    "endpoint": "http://localhost:8001"
  },
  "moondream": {
    "python_path": "python3",
    "model": "vikhyatk/moondream2",
    "mode": "local",
    "endpoint": "http://localhost:8002"
  },
  "aesthetic": {
    "mode": "docker",
    "endpoint": "http://localhost:8003",
    "metric": "clipiqa+"
  },
  "ai": {
    "provider": "claude"
  },
  "claude": {
    "api_key": "",
    "model": "claude-sonnet-4-20250514",
    "analysis_prompt": "(built-in default)"
  },
  "gemini": {
    "api_key": "",
    "model": "gemini-2.5-flash"
  },
  "rclone": {
    "rclone_path": "rclone",
    "remote_name": "gphotos",
    "remote_path": "album/Photostat",
    "upload_directories": ["/home/user/Pictures/NewImages"]
  }
}
```

---

## Settings Reference

### OpenSearch Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `opensearch.host` | string | `localhost` | OpenSearch server hostname or IP address |
| `opensearch.port` | integer | `9200` | OpenSearch HTTP port |
| `opensearch.ssl` | boolean | `false` | Enable HTTPS connection |
| `opensearch.index_name` | string | `photostat` | Name of the index to use |
| `opensearch.username` | string | `""` | Username for authentication (optional) |
| `opensearch.password` | string | `""` | Password for authentication (optional) |

### Indexing Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `indexing.directories` | array | `[]` | List of directories to index |
| `indexing.batch_size` | integer | `50` | Number of files to index per batch |
| `indexing.file_extensions` | array | See below | File extensions to include |

**Default file extensions:**
```json
[".jpg", ".jpeg", ".png", ".tiff", ".tif",
 ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf"]
```

File extensions can be configured in **Settings > Indexing** using checkboxes, or by editing `config.json` directly.

### UI Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `ui.thumbnail_size` | integer | `200` | Maximum thumbnail dimension in pixels |
| `ui.results_per_page` | integer | `50` | Number of results per page |

### ExifTool Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `exiftool.path` | string | `exiftool` | Path to ExifTool executable |
| `exiftool.use_for_raw` | boolean | `true` | Use ExifTool for RAW files |

### Sidecar Settings

PhotoStat stores custom metadata (persons, place, tags, rating, AI analysis cache, cloud upload tracking) in sidecar files that live next to the original image. Two formats are supported.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `sidecar.format` | string | `json` | Primary sidecar format: `json`, `xmp`, or `both` |
| `sidecar.read_both` | boolean | `true` | Fall back to the other format on read if the primary is missing |

**Format options:**

- **`json`** — writes `IMG_1234.jpg.photostat.json`, a compact JSON format. Default, and best if you don't use other photo tools alongside PhotoStat.
- **`xmp`** — writes `IMG_1234.jpg.xmp`, a standard XMP/RDF sidecar readable by Adobe Lightroom, Adobe Bridge, digiKam, ExifTool, and any tool that supports the XMP standard. Use this if you want your ratings/tags/persons to show up in external software.
- **`both`** — writes both files on every save. Useful during a migration period.

**XMP field mapping:** `rating` → `xmp:Rating`; `tags` → `dc:subject`; `persons` → `Iptc4xmpExt:PersonInImage`. PhotoStat-specific fields (`place`, `analysisHash`, `cloudUploads`) live in a custom namespace `http://photostat.app/xmp/1.0/` (prefix `photostat`) so they don't collide with other tools' fields. The `place` field uses the custom namespace because PhotoStat's place is free-form (values like "Restaurant" or "Beach") and would not be valid in a standard IPTC city field.

**Read-both fallback:** When `sidecar.read_both` is `true` (default), PhotoStat reads whichever format exists, regardless of the configured primary. This makes switching formats safe — you can flip `format` from `json` to `xmp` and old JSON sidecars will still be loaded until you rewrite them.

**Bulk conversion:** Use the **"Convert JSON sidecars to XMP…"** button on the Index tab to migrate an entire collection at once (available when `format` is `xmp` or `both`). See [User Guide — Sidecar Files](USER_GUIDE.md#sidecar-files) for the full workflow.

These settings can be configured via **Settings > Indexing > Sidecar Format** in the GUI, or edited directly in `config.json`.

### Logging Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `logging.enabled` | boolean | `false` | Enable file logging |
| `logging.level` | string | `INFO` | Log level: DEBUG, INFO, WARN, ERROR |
| `logging.max_log_size_mb` | integer | `5` | Maximum log file size in MB before rotation |
| `logging.max_log_files` | integer | `3` | Number of rotated log files to keep |

**Log file location:** `~/.photostat/photostat.log`

**Log rotation:** When the log file exceeds `max_log_size_mb`, it is rotated to `photostat.log.1` and older files shift up numerically. Files beyond `max_log_files` are deleted.

### Cache Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `cache.enabled` | boolean | `true` | Enable thumbnail disk cache |
| `cache.max_size_mb` | integer | `500` | Maximum cache size in MB |

**Cache location:** `~/.photostat/cache/`

### AI Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `ai.provider` | string | `claude` | AI provider: "claude", "gemini", or "moondream" |

### Claude Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `claude.api_key` | string | `""` | Your Anthropic API key |
| `claude.model` | string | `claude-sonnet-4-20250514` | Claude model to use |
| `claude.analysis_prompt` | string | (built-in) | Custom prompt for image analysis |

**Available Claude models:**
- `claude-sonnet-4-20250514` (recommended)
- `claude-opus-4-20250514`
- `claude-3-5-sonnet-20241022`
- `claude-3-5-haiku-20241022`

### Moondream Settings (Local AI)

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `moondream.python_path` | string | `python3` | Path to Python executable with moondream package |
| `moondream.model` | string | `vikhyatk/moondream2` | Moondream model identifier |
| `moondream.mode` | string | `local` | Backend: `local` (spawn Python) or `docker` (HTTP service) |
| `moondream.endpoint` | string | `http://localhost:8002` | Analysis service URL when `mode` is `docker` |

**Prerequisites:** `pip install "transformers>=4.51,<5" torch Pillow accelerate`

**Notes:** Moondream runs locally with no API key required. The model (~1.5 GB) is downloaded automatically on first use. GPU acceleration is **strongly recommended** — install PyTorch with CUDA support: `pip install torch --force-reinstall --index-url https://download.pytorch.org/whl/cu124`

### Aesthetic Scoring Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `aesthetic.mode` | string | `docker` | Backend mode (Docker HTTP service; no local-Python mode) |
| `aesthetic.endpoint` | string | `http://localhost:8003` | Aesthetic scoring service URL |
| `aesthetic.metric` | string | `clipiqa+` | Informational label of the IQA metric; the service's `PHOTOSTAT_IQA_METRIC` env var is authoritative |

**Notes:** Aesthetic scoring is Docker-only and writes the `aesthetic_score` field (0–100), separate from your manual `rating`. See [AESTHETIC_SCORING.md](AESTHETIC_SCORING.md).

### Face Recognition Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `faces.python_path` | string | `python3` | Path to Python executable with InsightFace |
| `faces.enabled` | boolean | `true` | Enable face recognition feature |
| `faces.confidence_threshold` | double | `0.6` | Minimum confidence for face detection (0.3-0.9) |
| `faces.cluster_threshold` | double | `0.6` | Similarity threshold for face clustering (0.3-0.9) |
| `faces.mode` | string | `local` | Backend: `local` (spawn Python) or `docker` (HTTP service) |
| `faces.endpoint` | string | `http://localhost:8001` | Faces service URL when `mode` is `docker` |

**Prerequisites:** `pip install insightface onnxruntime` (or `onnxruntime-gpu` for CUDA). Optionally `pip install scikit-learn` for DBSCAN clustering.

**Face data location:** `~/.photostat/faces/`

### Gemini Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `gemini.api_key` | string | `""` | Your Google API key |
| `gemini.model` | string | `gemini-2.5-flash` | Gemini model to use |

**Available Gemini models:**
- `gemini-2.5-flash` (recommended)
- `gemini-2.5-pro` (most capable)
- `gemini-2.5-flash-lite` (cheapest)

The model field accepts any current Gemini model id, so newer models can be
entered directly. The older 1.5 and 2.0 generations have been retired by Google
for image analysis and will return "model not found".

### rclone Settings (Cloud Upload)

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `rclone.rclone_path` | string | `rclone` | Path to rclone executable |
| `rclone.remote_name` | string | `""` | Name of the configured rclone remote (e.g., `gphotos`) |
| `rclone.remote_path` | string | `""` | Destination path on the remote (e.g., `upload` or `album/MyAlbum`) |
| `rclone.upload_directories` | array | `[]` | Local directories to upload |

**Prerequisites:** Install rclone from [rclone.org](https://rclone.org/downloads/) and configure a remote with `rclone config`.

**Google Drive:** Set `remote_path` to a folder path (e.g., `Photos/Processed`). Supports true incremental uploads — only new or modified files are transferred.

**Google Photos:** Set `remote_path` to `upload` (main library) or `album/AlbumName` (specific album). An empty path will fail. Note: Google Photos cannot detect previously uploaded files, so every run re-uploads all files. Use a staging folder to avoid duplicates.

**Google OAuth client ID:** rclone's default client ID is shared across all users and may be rate-limited by Google. If you experience authentication failures, create your own client ID in the [Google Cloud Console](https://console.cloud.google.com/) and enter it during `rclone config`. Enable the Google Drive API or Google Photos Library API as needed.

**Upload directories are separate from indexing directories.** This allows you to index your entire photo library but only upload specific folders.

---

## Supported Image Formats

### Native Support (No ExifTool Required)

| Format | Extensions |
|--------|------------|
| JPEG | .jpg, .jpeg |
| PNG | .png |
| TIFF | .tiff, .tif |
| GIF | .gif |
| BMP | .bmp |
| WebP | .webp |

### RAW Support (Requires ExifTool)

| Camera | Extensions |
|--------|------------|
| Canon | .cr2, .cr3 |
| Nikon | .nef, .nrw |
| Sony | .arw, .srf, .sr2 |
| Fujifilm | .raf |
| Olympus | .orf |
| Panasonic | .rw2 |
| Pentax | .pef |
| Adobe | .dng |
| Leica | .rwl |
| Samsung | .srw |

---

## Directory Structure

```
~/.photostat/
├── config.json              # Configuration file
├── photostat.log            # Log file (if logging enabled)
├── photostat.log.1          # Rotated log files
├── photostat.log.2
├── photostat_faces.py       # Face detection Python script (extracted from JAR)
├── photostat_moondream.py   # Moondream AI analysis script (extracted from JAR)
├── map.html                 # GPS map view (generated at runtime)
├── cache/                   # Thumbnail cache directory
│   └── *.jpg                # Cached thumbnails
└── faces/                   # Face recognition data
    ├── face_data.json       # Detected faces with embeddings
    ├── clusters.json        # Face clusters and person names
    ├── models/              # InsightFace model files (auto-downloaded)
    └── crops/               # Cached face crop thumbnails
        └── *.jpg
```
