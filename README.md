# PhotoStat Java

A JavaFX desktop application for indexing and searching image EXIF metadata using OpenSearch.

## Features

- **Image Indexing**: Index images from multiple directories with EXIF metadata extraction
- **Search**: Full-text search with faceted navigation
- **Thumbnails**: Automatic thumbnail generation for quick preview
- **Charts**: Visualizations of your photo collection (camera usage, timeline, exposure settings)
- **RAW Support**: Support for RAW formats (CR2, CR3, NEF, ARW, DNG, etc.) via ExifTool

## Requirements

- Java 21 or later
- Maven 3.6+
- OpenSearch 2.x running and accessible
- (Optional) ExifTool for RAW file support

## Building

```bash
# Build the project
mvn clean package

# Run tests
mvn test
```

## Running

### Option 1: Using Maven

```bash
mvn javafx:run
```

### Option 2: Using the JAR

```bash
java -jar target/photostat-java-1.0.0.jar
```

## Configuration

On first run, a configuration file is created at `~/.photostat/config.json`. You can also configure settings through the Settings dialog in the application.

### OpenSearch Connection

The default connection settings are:
- Host: localhost
- Port: 9200
- SSL: disabled
- Index name: photostat

### ExifTool

For RAW file support, install ExifTool:

- **Windows**: Download from https://exiftool.org/ and add to PATH
- **macOS**: `brew install exiftool`
- **Linux**: `sudo apt install libimage-exiftool-perl` or equivalent

## Usage

1. **Configure OpenSearch**: Click Settings and configure your OpenSearch connection
2. **Add Directories**: Go to the Index tab and add directories containing images
3. **Start Indexing**: Click "Start Indexing" to begin indexing images
4. **Search**: Use the Search tab to find images by text or filters
5. **View Charts**: The Charts tab shows visualizations of your collection

## Project Structure

```
photostat-java/
├── pom.xml                          # Maven configuration
├── src/main/java/com/photostat/
│   ├── App.java                     # Main application entry point
│   ├── models/
│   │   └── ImageMetadata.java       # Image metadata model
│   ├── services/
│   │   ├── ConfigService.java       # Configuration management
│   │   ├── ExifService.java         # EXIF metadata extraction
│   │   ├── OpenSearchService.java   # OpenSearch client
│   │   ├── IndexerService.java      # Background indexing
│   │   └── ThumbnailService.java    # Thumbnail generation
│   └── ui/
│       ├── MainWindow.java          # Main application window
│       ├── SearchPanel.java         # Search controls
│       ├── ResultsPanel.java        # Results table
│       ├── FacetsPanel.java         # Faceted navigation
│       ├── IndexPanel.java          # Directory management
│       ├── ChartsPanel.java         # Charts and visualizations
│       ├── DetailPanel.java         # Image detail view
│       ├── DirectoryBrowserDialog.java
│       └── SettingsDialog.java
├── src/main/resources/
│   ├── styles.css                   # JavaFX stylesheet
│   └── application.properties       # Default configuration
└── config.json                      # Sample configuration file
```

## Technology Stack

- **GUI**: JavaFX 21
- **EXIF Extraction**: metadata-extractor + ExifTool
- **Search Engine**: OpenSearch 2.x
- **JSON**: Jackson
- **Build**: Maven

## Supported Image Formats

### Native Support (metadata-extractor)
- JPEG (.jpg, .jpeg)
- PNG (.png)
- TIFF (.tiff, .tif)

### ExifTool Support (requires ExifTool)
- Canon RAW (.cr2, .cr3)
- Nikon RAW (.nef)
- Sony RAW (.arw)
- Olympus RAW (.orf)
- Panasonic RAW (.rw2)
- Adobe DNG (.dng)
- Fujifilm RAW (.raf)

## License

MIT License
