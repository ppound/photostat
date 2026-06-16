# Development Guide

This guide covers building PhotoStat from source and understanding the project structure.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Building from Source](#building-from-source)
- [Running from Source](#running-from-source)
- [Running Tests](#running-tests)
- [Project Structure](#project-structure)
- [Technology Stack](#technology-stack)
- [Architecture Overview](#architecture-overview)

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java JDK | 21 or later |
| Maven | 3.6 or later |
| Git | Any recent version |
| OpenSearch | 2.x (for testing) |

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/ppound/photostat.git
cd photostat-java

# Build the project (default: Apple Silicon + Windows + Linux)
mvn clean package

# Build with Intel Mac support instead of Apple Silicon
mvn clean package -Pmac-intel
```

### Build Output

| Build command | Output JAR | Platforms |
|---------------|------------|-----------|
| `mvn package` | `target/photostat-java-2.4.0-executable.jar` | Windows, Linux, macOS Apple Silicon |
| `mvn package -Pmac-intel` | `target/photostat-java-2.4.0-executable-mac-intel.jar` | Windows, Linux, macOS Intel |

The default build includes Apple Silicon (M1/M2/M3/M4) macOS natives. The `mac-intel` profile swaps these for Intel (x86_64) macOS natives and produces a JAR with a distinct filename.

---

## Running from Source

```bash
# Run with Maven JavaFX plugin
mvn javafx:run
```

Or run the main class directly:
```bash
mvn compile exec:java -Dexec.mainClass="com.photostat.App"
```

---

## Running Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ExifServiceTest

# Run with coverage report
mvn test jacoco:report
```

---

## Project Structure

```
photostat-java/
├── pom.xml                              # Maven configuration
├── README.md                            # Main documentation
├── docs/
│   ├── USER_GUIDE.md                    # User guide
│   ├── AI_ANALYSIS.md                   # AI analysis documentation
│   ├── CONFIGURATION.md                 # Configuration reference
│   ├── TROUBLESHOOTING.md               # Troubleshooting guide
│   ├── DEVELOPMENT.md                   # This file
│   └── screenshots/                     # Documentation screenshots
├── src/main/java/com/photostat/
│   ├── App.java                         # Main application entry (JavaFX)
│   ├── Launcher.java                    # JAR launcher (handles CLI vs GUI)
│   ├── cli/
│   │   ├── AnalyzeCli.java              # Command-line batch analysis
│   │   ├── DuplicatesCli.java           # Command-line duplicate finder
│   │   ├── FaceDetectCli.java           # Command-line face detection
│   │   └── ThumbnailCacheCli.java       # Command-line thumbnail caching
│   ├── models/
│   │   ├── ImageMetadata.java           # Image metadata model
│   │   ├── FaceDetection.java           # Face detection data model
│   │   └── FaceCluster.java             # Face cluster data model
│   ├── services/
│   │   ├── ConfigService.java           # Configuration management
│   │   ├── ExifService.java             # EXIF metadata extraction
│   │   ├── FileOperationsService.java   # Copy, move, rename, delete operations
│   │   ├── ImageAnalysisService.java    # AI image analysis (Claude/Gemini)
│   │   ├── OpenSearchService.java       # OpenSearch client
│   │   ├── IndexerService.java          # Background indexing
│   │   ├── ThumbnailService.java        # Thumbnail generation & caching
│   │   ├── HashService.java             # Content & perceptual image hashing
│   │   ├── FaceRecognitionService.java   # Face detection & clustering
│   │   ├── SidecarService.java          # Sidecar file management
│   │   └── LoggingService.java          # File-based logging
│   └── ui/
│       ├── MainWindow.java              # Main application window
│       ├── SearchPanel.java             # Search controls
│       ├── ResultsPanel.java            # Results table
│       ├── FacetsPanel.java             # Faceted navigation
│       ├── IndexPanel.java              # Directory management
│       ├── DuplicatesPanel.java         # Duplicate image detection
│       ├── FacesPanel.java              # Face recognition UI
│       ├── ChartsPanel.java             # Charts and visualizations
│       ├── DetailPanel.java             # Image detail view
│       ├── DirectoryBrowserDialog.java  # Directory browser
│       ├── SettingsDialog.java          # Settings dialog
│       └── MultiSelectAutoComplete.java # Chip-style multi-select component
└── src/main/resources/
    ├── styles.css                       # JavaFX stylesheet
    ├── photostat_faces.py               # Python face detection script
    └── application.properties           # Default configuration
```

---

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| GUI Framework | JavaFX | 21 |
| Search Engine | OpenSearch | 2.x |
| OpenSearch Client | opensearch-java | 2.10.0 |
| AI Analysis | Claude API (Anthropic) | - |
| AI Analysis | Gemini API (Google) | 1.5.0 |
| EXIF Extraction | metadata-extractor | 2.19.0 |
| HTTP Client | Apache HttpClient 5 | 5.3 |
| JSON Processing | Jackson | 2.17.0 |
| Build System | Maven | 3.6+ |
| Logging | SLF4J | 2.0.12 |

---

## Architecture Overview

### Entry Points

- **`Launcher.java`** - Main entry point for the executable JAR
  - Detects CLI mode (`--analyze`, `--cache-thumbnails`, `--find-duplicates`, `--detect-faces`, `--help`) vs GUI mode
  - Routes to `AnalyzeCli`, `ThumbnailCacheCli`, `DuplicatesCli`, `FaceDetectCli`, or `App` accordingly

- **`App.java`** - JavaFX application entry
  - Initializes the GUI
  - Creates the main window

- **`AnalyzeCli.java`** - CLI for batch AI analysis
- **`ThumbnailCacheCli.java`** - CLI for thumbnail pre-caching
- **`DuplicatesCli.java`** - CLI for finding duplicate images
- **`FaceDetectCli.java`** - CLI for batch face detection and clustering

### Services (Singleton Pattern)

All services use the singleton pattern with `getInstance()`:

- **`ConfigService`** - Loads/saves configuration from JSON
- **`OpenSearchService`** - Manages OpenSearch connection and queries
- **`ExifService`** - Extracts EXIF metadata from images
- **`IndexerService`** - Background indexing with progress reporting
- **`ThumbnailService`** - Generates and caches thumbnails
- **`HashService`** - Computes SHA-256 content hashes and perceptual hashes (dHash) for duplicate detection
- **`SidecarService`** - Manages `.photostat.json` and `.xmp` sidecar files (both backends)
- **`ImageAnalysisService`** - AI image analysis (Claude/Gemini)
- **`FaceRecognitionService`** - Face detection, clustering, and naming via Python sidecar
- **`FileOperationsService`** - Copy/move/rename/delete operations (iterates all sidecar backends so JSON and XMP sidecars travel with the image)
- **`LoggingService`** - File-based logging

### UI Components

- **`MainWindow`** - Main window with TabPane (Search, Index, Duplicates, Faces, Map, Charts)
- **`SearchPanel`** - Search input and filters
- **`ResultsPanel`** - TableView with thumbnails
- **`FacetsPanel`** - Faceted navigation sidebar
- **`DetailPanel`** - Image preview and metadata
- **`IndexPanel`** - Directory management and indexing controls
- **`DuplicatesPanel`** - Duplicate image detection and management
- **`FacesPanel`** - Face detection, clustering, and person naming
- **`ChartsPanel`** - Visualization charts

### Data Flow

1. **Indexing:**
   ```
   IndexPanel → IndexerService → ExifService    → OpenSearchService
                                 HashService     ↗
                                 SidecarService ↗
   ```

2. **Search:**
   ```
   SearchPanel → OpenSearchService → ResultsPanel
                                   → FacetsPanel
   ```

3. **AI Analysis:**
   ```
   ResultsPanel → ImageAnalysisService → Claude/Gemini API
                                       → SidecarService (save)
                                       → OpenSearchService (update)
   ```

4. **Duplicate Detection:**
   ```
   DuplicatesPanel → OpenSearchService (terms aggregation on hash fields)
                   → ThumbnailService (preview images)
   ```

### Threading Model

- **JavaFX Application Thread** - All UI updates
- **Background Tasks** - Indexing, analysis, thumbnail generation
- **ExecutorService** - Parallel CLI analysis

### Configuration

Configuration is stored in `~/.photostat/config.json` and managed by `ConfigService`. Changes made in `SettingsDialog` are persisted to this file.

---

## Adding New Features

### Adding a New Service

1. Create the service class in `src/main/java/com/photostat/services/`
2. Implement singleton pattern:
   ```java
   public class MyService {
       private static MyService instance;

       public static synchronized MyService getInstance() {
           if (instance == null) {
               instance = new MyService();
           }
           return instance;
       }

       private MyService() {
           // Initialize
       }
   }
   ```
3. Add configuration options to `ConfigService` if needed

### Adding a New UI Panel

1. Create the panel class in `src/main/java/com/photostat/ui/`
2. Extend `VBox`, `HBox`, or `BorderPane` as appropriate
3. Add to `MainWindow` or the relevant parent component

### Adding a New CLI Command

1. Add argument parsing in `AnalyzeCli.parseArgs()`
2. Implement the command logic
3. Update help text
