# PhotoStat

A powerful cross-platform desktop application for indexing, searching, and analyzing your photo collection using EXIF metadata. Built with JavaFX and powered by OpenSearch for fast, full-text search capabilities.

![PhotoStat Main Window](docs/screenshots/main-window.png)

---

## Why PhotoStat?

After years of photography and using various software like Lightroom, Capture One, Photoshop, and others to process images, many photographers find themselves with thousands of photos scattered across different applications, catalogs, and drives. Each tool has its own proprietary database, making it difficult to get a unified view of your entire collection.

**PhotoStat was built to solve this problem:**

- **Unified Search Across All Your Photos** - Index images from multiple directories and drives into a single searchable database
- **Find Duplicates** - Find and manage duplicate images
- **No Vendor Lock-In** - Your metadata stays with your photos via portable sidecar files (JSON or industry-standard XMP, readable by Lightroom, Bridge, digiKam, and ExifTool)
- **Face Recognition** - Automatic face detection and clustering via InsightFace, with person naming and search integration
- **Cloud Upload** - Upload photos to Google Photos, S3, or 70+ cloud providers via rclone
- **Cross-Platform** - Native installers for Windows (.msi) and macOS (.dmg), plus a cross-platform JAR for Linux and other systems
- **AI-Powered Organization** - Leverage Claude, Gemini, or Ollama-compatible local AI to automatically tag and categorize your photos
- **Find Your Best — and Cull the Rest** - Score photos for aesthetic quality with a free local AI model, then sort or filter by that score or your own star ratings to surface keepers fast, or sort ascending to find and delete the weakest shots
- **AI Image Generation** - Generate new images from your photos using Luma AI with text prompts and reference images

---

## Features

### Core Capabilities
- **Fast Full-Text Search** - Search across all EXIF metadata fields instantly
- **Faceted Navigation** - Filter by camera, lens, file type, ISO, date, and more
- **Flexible Sorting** - Sort results by date taken, aesthetic score, or rating, ascending or descending — put your best photos first, or sort ascending to surface the weakest for culling
- **On This Day** - Revisit photos taken on this calendar day across all years, with an optional ± window for multi-day trips
- **Thumbnail Preview** - Quick visual preview of search results
- **Multi-Directory Indexing** - Index photos from multiple locations with selective directory choice
- **Background Indexing** - Continue working while photos are being indexed
- **Interactive GPS Map** - Browse geotagged photos on an interactive OpenStreetMap with clustering

### Metadata & Organization
- **Complete EXIF Support** - Camera, lens, exposure, GPS, and more
- **Custom Metadata** - Add persons, places, tags, and ratings
- **Keyboard Rating** - Press 1-5 to rate, 0 to clear — instant save for fast culling
- **Slideshow Mode** - Full-screen browsing with keyboard navigation, quick rating, and image deletion
- **Batch Rename** - Substring or regex find/replace across selected images or the full current result set, with live preview and conflict detection
- **Dark Theme** - Switch between light and dark themes in Settings for comfortable low-light use
- **Sidecar Files** - Metadata persists with your images in JSON or XMP format (XMP is readable by Lightroom, Bridge, digiKam, ExifTool, and other standard photo tools)
- **Copy & Paste Metadata** - Quickly apply tags across multiple images

### AI Analysis
- **Multiple Providers** - Claude (Anthropic), Gemini (Google), Ollama (local), or Moondream (local)
- **Smart Tagging** - Automatic subject, style, and mood detection
- **Quality Rating** - AI-generated ratings based on composition
- **Batch Processing** - Analyze via GUI or command-line
- **Cost Tracking** - Monitor token usage and estimated costs
- **No-install Docker option** - Run Moondream as a container instead of a local Python setup (see [docker/README.md](docker/README.md))

> See [docs/AI_ANALYSIS.md](docs/AI_ANALYSIS.md) for API key setup, CLI usage, cost tracking, and provider configuration.

### Aesthetic Scoring
- **Local Quality Scoring** - Score photos for aesthetic/perceptual quality (0–100) with a local, free IQA model — no API key
- **Find Your Best (and Worst)** - Sort, filter, and facet on the score to surface top shots or find low-quality images to cull
- **Separate from Ratings** - The AI score lives in its own field and never overwrites your manual star ratings
- **GPU-Accelerated** - Uses your NVIDIA GPU when available; score a large library in minutes
- **GUI & CLI** - Score in bulk (Index → Score Photos), per selection (AI → Score Selected), or via `--score-aesthetics`

> See [docs/AESTHETIC_SCORING.md](docs/AESTHETIC_SCORING.md) for setup, scoring workflows, and metric options.

### AI Image Generation
- **Luma AI Integration** - Generate new images using Luma's Photon model with text prompts
- **Reference Images** - Use your photos as image references, style references, or source images for modification
- **Configurable Options** - Aspect ratio, reference weight, and reference type controls
- **Save & Index** - Save generated images to any folder with optional OpenSearch indexing

> See [docs/AI_ANALYSIS.md](docs/AI_ANALYSIS.md#ai-image-generation-luma) for setup instructions.

### Face Recognition
- **Automatic Detection** - Detect faces in your photo collection using InsightFace (Python)
- **Face Clustering** - Automatically group similar faces using DBSCAN or centroid-based clustering
- **Person Naming** - Assign names to face clusters; names are saved to OpenSearch and sidecar files
- **Cluster Merging** - Merge clusters that belong to the same person
- **Incremental Scanning** - Only new images are processed on re-runs; safe to interrupt and resume
- **GPU Acceleration** - Automatically uses CUDA GPU when available for faster detection
- **No-install Docker option** - Run InsightFace as a container instead of a local Python setup (see [docker/README.md](docker/README.md))
- **CLI Support** - Batch face detection with parallel workers via `--detect-faces`

> See [docs/FACE_RECOGNITION.md](docs/FACE_RECOGNITION.md) for full setup instructions, Windows GPU configuration, and workflow details.

### Duplicate Detection
- **Exact Duplicates** - SHA-256 content hashing finds byte-for-byte copies
- **Visual Duplicates** - Perceptual hashing (dHash) finds resized, recompressed, or re-exported copies
- **Reclaimable Space** - See how much disk space you can recover
- **Bulk Delete** - Select and remove duplicates with confirmation

### Cloud Upload
- **rclone Integration** - Upload photos to 70+ cloud providers (Google Photos, S3, Dropbox, etc.)
- **Upload Selected Images** - Select images from search results and upload to any remote with a progress dialog
- **Duplicate Upload Prevention** - Tracks which remotes each file has been uploaded to; skips already-uploaded files automatically
- **Separate Upload Directories** - Upload directories are independent from indexing directories
- **GUI & CLI** - Upload via the toolbar button with progress dialog, or schedule via `--rclone-upload`
- **Incremental Uploads** - rclone only uploads new/changed files
- **Sidecar Exclusion** - `.photostat.json` sidecar files are automatically excluded

### Backend Services
- **Guided Setup** - A first-run wizard installs Docker (with your consent), starts the engine, and brings up the backend containers
- **One-Click Start/Stop** - Start and stop the whole backend from the **Services** tab, or one service at a time
- **Live Status** - Container state and health for each service, with a log of what Docker is doing
- **Optional Autostart** - Start the services when PhotoStat opens and stop them when it closes
- **Local Only** - Every service port is bound to `127.0.0.1`, so nothing is exposed to your network

### Visualizations
- **Camera Usage Charts** - See which cameras and lenses you use most
- **Timeline View** - Visualize your collection over time
- **Exposure Analysis** - ISO, aperture, and focal length distributions
- **Processing Software**
- **GPS Map** - Interactive map view of geotagged photos with cluster and marker modes

---

## Quick Start

Install PhotoStat, then let the built-in setup wizard handle everything else.

### 1. Download & Install PhotoStat

Download the latest release from **[GitHub Releases](https://github.com/ppound/photostat/releases)**. Choose the option that fits your platform:

#### Option A: Windows Installer (.msi)

Download `PhotoStat-2.6.1.msi`, double-click to install, and launch from the Start Menu. No Java installation required.

#### Option B: macOS Installer (.dmg) — Apple Silicon only

Download `PhotoStat-2.6.1-apple-silicon.dmg`, open it, and drag PhotoStat to your Applications folder. No Java installation required.

> **Note:** The macOS installer is unsigned. On first launch, **right-click** the app in Finder and select **Open**, then click **Open** in the dialog. If that doesn't work, go to **System Settings → Privacy & Security** and click **Open Anyway** next to the blocked app message. See [Troubleshooting](docs/TROUBLESHOOTING.md#macos-photostat-is-damaged-or-cannot-be-opened) for details.
>
> **Intel Mac users:** A DMG installer is not available for Intel Macs. Use the cross-platform JAR below (`photostat-java-2.6.1-executable-mac-intel.jar`).

#### Option C: Cross-platform JAR

Download `photostat-java-2.6.1-executable.jar`. Requires Java 21+. This JAR includes native libraries for **Windows**, **Linux**, and **macOS Apple Silicon** (M1/M2/M3/M4).

```bash
java -jar photostat-java-2.6.1-executable.jar
```

**Intel Mac users:** Download the separate `photostat-java-2.6.1-executable-mac-intel.jar` which includes Intel (x86_64) macOS natives instead of Apple Silicon. See [Troubleshooting](docs/TROUBLESHOOTING.md#error-on-intel-mac-no-suitable-pipeline-found-or-graphics-errors).

> Java is only needed for the cross-platform JAR. The `.msi` and `.dmg` installers bundle their own Java 21 runtime.

### 2. Run the setup wizard

The first time PhotoStat starts it offers to set up its backend services. The wizard:

1. Checks whether Docker is installed, and offers to install Docker Desktop if not
2. Starts the Docker engine if it isn't already running
3. Lets you choose a CPU or GPU profile and which services to run
4. Downloads the container images and starts everything

That gives you OpenSearch (the search index, required) plus the optional AI backends for face recognition, tagging/captioning, and aesthetic scoring — with no Python, PyTorch or InsightFace to install by hand.

You can reopen the wizard at any time from **Services → Setup...**, and start or stop the services from that same tab.

**Before it installs anything**, the wizard shows exactly what Docker Desktop changes on your machine — on Windows that includes enabling WSL 2, which puts Windows under a hypervisor and can affect other virtualisation software like VirtualBox or VMware. It also links Docker's licence terms, which require a paid subscription for commercial use in larger organisations. Nothing is installed until you tick the consent box, and PhotoStat never restarts your machine.

**None of this is required.** PhotoStat works against an OpenSearch server you already run, with the AI features in local Python mode or switched off entirely. Click Cancel to skip the wizard and set things up yourself.

### 3. Manual setup (advanced)

<details>
<summary>Set up Docker and OpenSearch yourself instead of using the wizard</summary>

**Install Docker:**

| Platform | Installation |
|----------|-------------|
| **Windows** | Download [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/) — requires WSL 2 (the installer will guide you) |
| **macOS** | Download [Docker Desktop for Mac](https://www.docker.com/products/docker-desktop/) — choose Apple Silicon or Intel chip |
| **Linux** | Install via your package manager (see below) |

```bash
# Ubuntu/Debian
sudo apt update && sudo apt install docker.io
sudo systemctl enable --now docker
sudo usermod -aG docker $USER   # Log out and back in after this

# Fedora/RHEL
sudo dnf install docker
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
```

Verify with `docker --version` and `docker run hello-world`.

**Run the backend services** using the compose file PhotoStat deploys to `~/.photostat`:

```bash
# All services, CPU
docker compose -f ~/.photostat/docker-compose.yml up -d

# GPU (NVIDIA)
docker compose -f ~/.photostat/docker-compose.yml -f ~/.photostat/docker-compose.gpu.yml up -d

# Only what you need
docker compose -f ~/.photostat/docker-compose.yml up -d opensearch aesthetic
```

See [docker/README.md](docker/README.md) for the full reference.

**Or run OpenSearch alone**, without the AI backends:

```bash
docker volume create opensearch-data

docker run -d --name opensearch \
  -p 127.0.0.1:9200:9200 \
  -v opensearch-data:/usr/share/opensearch/data \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  opensearchproject/opensearch:2.11.1
```

The `127.0.0.1:` prefix matters — security is disabled here, so without it your photo index would be readable and writable by anyone on your network. Data lives in the named volume, so it survives `docker stop`, `docker start` and `docker rm`.

Or download OpenSearch from [opensearch.org](https://opensearch.org/downloads.html).

</details>

### 4. Get Started

1. Check the OpenSearch connection via **File > Settings** (the wizard's defaults should already work)
2. Add photo directories in the **Index** tab
3. Click **Start Indexing**
4. Search your photos in the **Search** tab

---

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](docs/USER_GUIDE.md) | Detailed usage instructions for all features |
| [AI Analysis](docs/AI_ANALYSIS.md) | AI setup, CLI mode, and cost tracking |
| [Aesthetic Scoring](docs/AESTHETIC_SCORING.md) | Local image-quality scoring: setup, workflows, and metrics |
| [Face Recognition](docs/FACE_RECOGNITION.md) | Python setup, GPU acceleration, and face detection workflow |
| [Configuration](docs/CONFIGURATION.md) | All settings and options explained |
| [Docker Backends](docker/README.md) | The Services tab and setup wizard, plus running the containers by hand (CPU or GPU) |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues and solutions |
| [Development](docs/DEVELOPMENT.md) | Building from source and project structure |

---

## Command-Line Interface

PhotoStat includes a CLI for batch image analysis. The CLI requires the cross-platform JAR and Java 21+ — the native installers (MSI/DMG) are for the GUI only.

```bash
# Analyze all configured directories
java -jar photostat-java-2.6.1-executable.jar --analyze

# Preview what would be analyzed
java -jar photostat-java-2.6.1-executable.jar --analyze --dry-run

# Run with 4 parallel threads
java -jar photostat-java-2.6.1-executable.jar --analyze --parallel 4

# Use Gemini instead of Claude
java -jar photostat-java-2.6.1-executable.jar --analyze --provider gemini

# Use Ollama (local)
java -jar photostat-java-2.6.1-executable.jar --analyze --provider ollama

# Find duplicate images
java -jar photostat-java-2.6.1-executable.jar --find-duplicates

# Find visually similar images
java -jar photostat-java-2.6.1-executable.jar --find-duplicates --mode visual

# Detect and cluster faces
java -jar photostat-java-2.6.1-executable.jar --detect-faces

# Face detection with 4 parallel workers
java -jar photostat-java-2.6.1-executable.jar --detect-faces --parallel 4

# Face detection on a specific directory
java -jar photostat-java-2.6.1-executable.jar --detect-faces --dir /path/to/photos

# Score photos for aesthetic quality (requires the aesthetic Docker backend)
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics

# Re-score everything, larger batches
java -jar photostat-java-2.6.1-executable.jar --score-aesthetics --force --batch 32

# Upload to cloud via rclone
java -jar photostat-java-2.6.1-executable.jar --rclone-upload

# Preview what would be uploaded
java -jar photostat-java-2.6.1-executable.jar --rclone-upload --dry-run
```

See [AI Analysis - CLI](docs/AI_ANALYSIS.md#command-line-interface-cli) for full documentation.

---

## Supported Formats

### Native Support
JPEG, PNG, TIFF, GIF, BMP, WebP

### RAW Support (requires ExifTool)
Canon (CR2, CR3), Nikon (NEF), Sony (ARW), Fujifilm (RAF), and more.

Install ExifTool:
- **Windows:** Download from [exiftool.org](https://exiftool.org/)
- **macOS:** `brew install exiftool`
- **Linux:** `sudo apt install libimage-exiftool-perl`

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| GUI | JavaFX 21 |
| Search | OpenSearch 2.x |
| AI | Claude API, Gemini API, Ollama (local), Luma AI |
| Face Recognition | InsightFace (Python sidecar) |
| EXIF | metadata-extractor, ExifTool |
| Build | Maven |

---

## License

MIT License - See [LICENSE](LICENSE) for details.

---

## Links

- [GitHub Repository](https://github.com/ppound/photostat)
- [Report Issues](https://github.com/ppound/photostat/issues)
- [Releases](https://github.com/ppound/photostat/releases)
