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
  "sidecar": {
    "enabled": true
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
    "model": "gemini-2.0-flash"
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

### Sidecar Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `sidecar.enabled` | boolean | `true` | Save custom metadata to sidecar files |

### AI Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `ai.provider` | string | `claude` | AI provider: "claude" or "gemini" |

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

### Gemini Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `gemini.api_key` | string | `""` | Your Google API key |
| `gemini.model` | string | `gemini-2.0-flash` | Gemini model to use |

**Available Gemini models:**
- `gemini-2.0-flash` (recommended)
- `gemini-1.5-flash`
- `gemini-1.5-pro`

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
├── config.json          # Configuration file
├── photostat.log        # Log file (if logging enabled)
├── photostat.log.1      # Rotated log files
├── photostat.log.2
├── map.html             # GPS map view (generated at runtime)
└── cache/               # Thumbnail cache directory
    └── *.jpg            # Cached thumbnails
```
