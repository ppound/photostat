# PhotoStat Java

A powerful cross-platform desktop application for indexing, searching, and analyzing your photo collection using EXIF metadata. Built with JavaFX and powered by OpenSearch for fast, full-text search capabilities.

![PhotoStat Main Window](docs/screenshots/main-window.png)

---

## Why PhotoStat?

After years of photography and using various software like Lightroom, Capture One, Photoshop, and others to process images, many photographers find themselves with thousands of photos scattered across different applications, catalogs, and drives. Each tool has its own proprietary database, making it difficult to get a unified view of your entire collection.

**PhotoStat was built to solve this problem:**

- **Unified Search Across All Your Photos** - Index images from multiple directories and drives into a single searchable database
- **No Vendor Lock-In** - Your metadata stays with your photos via portable JSON sidecar files
- **Cross-Platform** - Native installers for Windows (.msi) and macOS (.dmg), plus a cross-platform JAR for Linux and other systems
- **AI-Powered Organization** - Leverage Claude or Gemini AI to automatically tag and categorize your photos

---

## Features

### Core Capabilities
- **Fast Full-Text Search** - Search across all EXIF metadata fields instantly
- **Faceted Navigation** - Filter by camera, lens, file type, ISO, date, and more
- **Thumbnail Preview** - Quick visual preview of search results
- **Multi-Directory Indexing** - Index photos from multiple locations
- **Background Indexing** - Continue working while photos are being indexed

### Metadata & Organization
- **Complete EXIF Support** - Camera, lens, exposure, GPS, and more
- **Custom Metadata** - Add persons, places, tags, and ratings
- **Sidecar Files** - Metadata persists with your images
- **Copy & Paste Metadata** - Quickly apply tags across multiple images

### AI Analysis
- **Multiple Providers** - Claude (Anthropic) or Gemini (Google)
- **Smart Tagging** - Automatic subject, style, and mood detection
- **Quality Rating** - AI-generated ratings based on composition
- **Batch Processing** - Analyze via GUI or command-line
- **Cost Tracking** - Monitor token usage and estimated costs

### Visualizations
- **Camera Usage Charts** - See which cameras and lenses you use most
- **Timeline View** - Visualize your collection over time
- **Exposure Analysis** - ISO, aperture, and focal length distributions

---

## Quick Start

### 1. Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| OpenSearch | 2.x | Required for all installation methods |
| Java | 21 or later | Only needed for the cross-platform JAR — installers bundle their own runtime |

### 2. Install OpenSearch

**Docker (Recommended):**

Pull the OpenSearch image:
```bash
docker pull opensearchproject/opensearch:2.11.0
```

Create a volume for persistent storage and run the container:
```bash
docker volume create opensearch-data

docker run -d --name opensearch \
  -p 9200:9200 \
  -v opensearch-data:/usr/share/opensearch/data \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  opensearchproject/opensearch:2.11.0
```

This ensures your indexed data survives container restarts and removals. To manage the container:
```bash
docker stop opensearch     # Stop the container
docker start opensearch    # Start it again (data is preserved)
docker rm opensearch       # Remove the container (volume keeps data)
```

Or download from [opensearch.org](https://opensearch.org/downloads.html).

### 3. Download & Install PhotoStat

Download the latest release from **[GitHub Releases](https://github.com/ppound/photostat/releases)**. Choose the option that fits your platform:

#### Option A: Windows Installer (.msi)

Download `PhotoStat-1.6.3.msi`, double-click to install, and launch from the Start Menu. No Java installation required.

#### Option B: macOS Installer (.dmg)

Download `PhotoStat-1.6.3.dmg`, open it, and drag PhotoStat to your Applications folder. No Java installation required.

> **Note:** The macOS installer is unsigned. On first launch, right-click the app and select **Open**, then confirm.

#### Option C: Cross-platform JAR

Download `photostat-java-1.6.3-executable.jar`. Requires Java 21+.

```bash
java -jar photostat-java-1.6.3-executable.jar
```

**Apple Silicon Mac (M1/M2/M3):** See [Troubleshooting](docs/TROUBLESHOOTING.md#error-on-apple-silicon-mac-no-suitable-pipeline-found-or-graphics-errors).

### 4. Get Started

1. Configure OpenSearch connection via **File > Settings**
2. Add photo directories in the **Index** tab
3. Click **Start Indexing**
4. Search your photos in the **Search** tab

---

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](docs/USER_GUIDE.md) | Detailed usage instructions for all features |
| [AI Analysis](docs/AI_ANALYSIS.md) | AI setup, CLI mode, and cost tracking |
| [Configuration](docs/CONFIGURATION.md) | All settings and options explained |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues and solutions |
| [Development](docs/DEVELOPMENT.md) | Building from source and project structure |

---

## Command-Line Interface

PhotoStat includes a CLI for batch image analysis:

```bash
# Analyze all configured directories
java -jar photostat-java-1.6.3-executable.jar --analyze

# Preview what would be analyzed
java -jar photostat-java-1.6.3-executable.jar --analyze --dry-run

# Run with 4 parallel threads
java -jar photostat-java-1.6.3-executable.jar --analyze --parallel 4

# Use Gemini instead of Claude
java -jar photostat-java-1.6.3-executable.jar --analyze --provider gemini
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
| AI | Claude API, Gemini API |
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
