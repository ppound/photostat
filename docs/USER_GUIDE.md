# PhotoStat User Guide

This guide covers the day-to-day usage of PhotoStat for managing and searching your photo collection.

## Table of Contents

- [First Launch](#first-launch)
- [Configuring OpenSearch](#configuring-opensearch)
- [Adding Directories](#adding-directories)
- [Indexing Images](#indexing-images)
- [Searching Your Photos](#searching-your-photos)
- [Using Faceted Navigation](#using-faceted-navigation)
- [Viewing Image Details](#viewing-image-details)
- [Adding Custom Metadata](#adding-custom-metadata)
- [Slideshow Mode](#slideshow-mode)
- [Managing Files](#managing-files)
- [Finding Duplicates](#finding-duplicates)
- [Exploring Charts](#exploring-charts)
- [Thumbnail Cache](#thumbnail-cache)
- [Sidecar Files](#sidecar-files)
- [Large Collections](#large-collections)
- [Dark Theme](#dark-theme)
- [Enabling Logging](#enabling-logging)

---

## First Launch

When you first launch PhotoStat, you'll see the main window with four tabs:

- **Search** - Find and browse your indexed photos
- **Index** - Manage directories and run indexing
- **Duplicates** - Find and manage duplicate images
- **Charts** - View visualizations of your collection

![Application Tabs](screenshots/app-tabs.png)

---

## Configuring OpenSearch

1. Click **File > Settings** or the **Settings** button

   ![Settings Button](screenshots/settings-button.png)

2. In the Settings dialog, configure your OpenSearch connection:

   | Setting | Default | Description |
   |---------|---------|-------------|
   | Host | localhost | OpenSearch server hostname |
   | Port | 9200 | OpenSearch HTTP port |
   | Use SSL | No | Enable for HTTPS connections |
   | Index Name | photostat | Name of the search index |

   ![Settings Dialog](screenshots/settings-dialog.png)

3. Click **Test Connection** to verify connectivity

4. Click **Save** to apply changes

---

## Adding Directories

1. Navigate to the **Index** tab

   ![Index Tab](screenshots/index-tab.png)

2. Click **Add Directory** button

3. In the directory browser:
   - Navigate to your photo folder
   - Or use the drive buttons for quick access
   - Click **Select** when you've chosen your folder

   ![Directory Browser](screenshots/directory-browser.png)

4. The directory will appear in the list with its path

5. Repeat to add more directories as needed

**Tips:**
- Add your main photo library folder (e.g., `Pictures`, `Photos`)
- You can add network drives if they're mounted
- Subdirectories are automatically included

---

## Indexing Images

1. In the **Index** tab, verify your directories are listed

2. Click **Start Indexing**

   ![Start Indexing](screenshots/start-indexing.png)

3. Watch the progress bar and status messages:
   - "Scanning directories..." - Finding image files
   - "Indexing: filename.jpg" - Processing individual files
   - "Indexed X of Y files" - Progress update

   ![Indexing Progress](screenshots/indexing-progress.png)

4. Wait for "Indexing complete" message

**Configuring File Types:**

You can choose which file types to index in **File > Settings > Indexing**. Each supported extension has a checkbox — uncheck TIFF and RAW formats if you want faster indexing of just standard image formats.

**Notes:**
- Indexing runs in the background - you can switch tabs
- Large collections may take several minutes
- Only new/modified files are re-indexed on subsequent runs
- Click **Stop Indexing** to cancel if needed
- TIFF and RAW files are slower to index and require ExifTool

---

## Searching Your Photos

1. Navigate to the **Search** tab

   ![Search Tab](screenshots/search-tab.png)

2. **Text Search**: Type keywords in the search box
   - Camera names: "Canon", "Nikon D850"
   - Lens info: "50mm", "70-200"
   - File names: "vacation", "2023"
   - Any EXIF field value

3. **Filter Options**:

   | Filter | Description |
   |--------|-------------|
   | Camera Make | Filter by manufacturer with autocomplete (Canon, Nikon, Sony...) |
   | Camera Model | Filter by specific camera model with autocomplete |
   | Lens | Filter by lens used with autocomplete |
   | File Type | JPEG, PNG, RAW formats |
   | Date Range | Photos taken between dates |
   | ISO Range | Filter by ISO sensitivity |
   | Aperture | Filter by f-stop value |
   | Person | Multi-select with chips - filter by tagged people |
   | Place | Multi-select with chips - filter by location |
   | Tags | Multi-select with chips - filter by custom tags |
   | Rating | Filter by quality rating with autocomplete |

   **Autocomplete**: Camera, lens, and rating fields support type-ahead filtering - start typing to narrow down options.

   **Multi-Select Chips**: Person, Place, and Tag filters display selected values as removable chips, allowing you to filter by multiple values simultaneously (AND logic - all selected values must match).

   ![Search Filters](screenshots/search-filters.png)

4. Click **Search** or press Enter

5. Results appear in the table below with:
   - Thumbnail preview
   - Filename
   - Camera info
   - Date taken
   - Exposure settings

   ![Search Results](screenshots/search-results.png)

---

## Using Faceted Navigation

The **Facets Panel** on the left shows aggregated counts for quick filtering:

![Facets Panel](screenshots/facets-panel.png)

**Available Facets:**
- **Camera Make** - Click to filter by manufacturer
- **Camera Model** - Click to filter by specific model
- **Lens Model** - Click to filter by lens
- **Software** - Click to filter by editing software
- **File Type** - Click to filter by format
- **ISO Range** - Click to filter by ISO bracket
- **Year/Month** - Click to filter by time period
- **Persons** - Click to filter by tagged people
- **Places** - Click to filter by location name
- **Tags** - Click to filter by custom tags

**How to Use:**
1. Click any facet value to apply that filter
2. The search results update immediately
3. Other facets update to show remaining options
4. For Person, Place, and Tag facets: clicking adds to existing selections (displayed as chips)
5. Remove individual filters by clicking the X on chips, or use Clear Filters to reset all

---

## Viewing Image Details

1. Click on any row in the search results

2. The **Detail Panel** on the right shows:

   ![Detail Panel](screenshots/detail-panel.png)

   **Preview Image** - Larger thumbnail of the selected photo

   **File Information:**
   - Full file path
   - File size
   - Image dimensions
   - Date taken

   **Camera Information:**
   - Make and model
   - Lens used
   - Software/editor

   **Exposure Settings:**
   - ISO
   - Aperture (f-stop)
   - Shutter speed
   - Focal length

   **GPS Location** (if available):
   - Latitude/Longitude
   - "Open in Google Maps" link

3. **Action Buttons:**
   - **Open in Viewer** - Opens the image in your default photo viewer
   - **Open Folder** - Opens the containing folder in file explorer

---

## Adding Custom Metadata

You can add your own metadata to photos for better organization:

1. **Select an image** in the search results

2. In the **Detail Panel**, find the **Custom Metadata** section:

   ![Custom Metadata](screenshots/custom-metadata.png)

3. **Add metadata:**

   | Field | Description | Example |
   |-------|-------------|---------|
   | **Persons** | Names of people in the photo (comma-separated) | "John, Jane, Bob" |
   | **Place** | Location name | "Central Park, NYC" |
   | **Tags** | Custom tags (comma-separated) | "vacation, family, summer" |
   | **Rating** | Quality rating using asterisks | "***" (3 stars) |

4. Click **Save Metadata** to save changes

5. The search will automatically refresh

**Keyboard Rating Shortcuts:**

For fast photo culling, you can rate images directly from the results table using keyboard shortcuts:

1. Click the results table to give it focus
2. Use **Arrow Up/Down** to navigate between images
3. Press **1-5** to set a rating (1 = \*, 2 = \*\*, ..., 5 = \*\*\*\*\*)
4. Press **0** to clear the rating

Ratings are saved immediately to OpenSearch and sidecar files — no need to click Save. The Rating column in the table and the Detail Panel update instantly. These shortcuts only work when the results table has focus, so typing in the search box or metadata fields is unaffected.

**Using Custom Metadata:**
- Search for names, places, or tags in the search box
- Use the **Persons**, **Places**, and **Tags** facets to filter
- Use the filter dropdowns in the **Custom Metadata** section of the search panel

**Copy & Paste Metadata:**

You can copy custom metadata from one image and paste it to others:

1. Select an image with the metadata you want to copy
2. Click **Copy** in the Custom Metadata section
3. Select another image
4. Click **Paste** to fill in the fields
5. Click **Save** to apply the changes

This is useful when multiple photos share the same people, location, or tags.

---

## Slideshow Mode

PhotoStat includes a full-screen slideshow for browsing your current search results with keyboard navigation and quick rating — ideal for photo culling workflows.

### Launching the Slideshow

- Click the **Slideshow** button in the results toolbar, or
- Press **F5** when the results table has focus

The slideshow opens full-screen starting from the currently selected image (or the first result if none is selected).

### Keyboard Controls

| Key | Action |
|-----|--------|
| **Right / Down / Space / Page Down** | Next image |
| **Left / Up / Page Up** | Previous image |
| **Home** | Jump to first image |
| **End** | Jump to last image |
| **1-5** (digit or numpad) | Set rating (1 = ★, 5 = ★★★★★) |
| **0** (digit or numpad) | Clear rating |
| **I** | Toggle HUD info overlay |
| **Delete / Backspace** | Delete current image |
| **Escape** | Exit slideshow |

### HUD Overlay

A semi-transparent info bar appears at the bottom of the screen showing:
- Current rating (gold stars)
- Filename
- EXIF summary (aperture, shutter speed, ISO, focal length)
- Image counter (e.g., "3 / 150")

The HUD auto-hides after 3 seconds and reappears on any key press or mouse movement. Press **I** to pin it on or off.

### Rating in Slideshow

Press **1-5** to rate the current image or **0** to clear the rating. A brief toast notification confirms the rating. Changes are saved immediately to OpenSearch and sidecar files, and the results table updates when you exit the slideshow.

### Deleting Images in Slideshow

Press **Delete** or **Backspace** to delete the current image. A confirmation dialog appears before anything is removed. On confirmation, the image file, its sidecar file, and its index entry are all deleted. The slideshow advances to the next image automatically.

### RAW File Support

Standard image formats (JPEG, PNG, etc.) are loaded at full screen resolution. RAW files (CR2, CR3, NEF, ARW, DNG, etc.) are displayed using embedded preview thumbnails extracted via the thumbnail cache.

---

## Managing Files

You can copy, move, or delete images directly from search results:

![File Operations Toolbar](screenshots/file-operations-toolbar.png)

**Selecting Images:**
1. **Single selection** - Click on a row to select one image
2. **Multi-select** - Use Ctrl+Click to add individual images to selection
3. **Range select** - Use Shift+Click to select a range of images

**Available Operations:**

| Operation | Button | Description |
|-----------|--------|-------------|
| **Copy** | Copy Selected... | Copy images to a new directory |
| **Move** | Move Selected... | Move images to a new directory |
| **Delete** | Delete Selected | Permanently delete images |

**Copy Images:**
1. Select one or more images
2. Click **Copy Selected...**
3. Choose a destination directory
4. After copying, you'll be asked if you want to index the copied files
   - Click **OK** to add them to the search index
   - Click **Cancel** to skip indexing
5. A summary shows the result and indexing status

**Move Images:**
1. Select one or more images
2. Click **Move Selected...**
3. Choose a destination directory
4. Files are moved and automatically re-indexed at the new location
5. A summary shows the result and indexing status

**Delete Images:**
1. Select one or more images
2. Click **Delete Selected**
3. Confirm the deletion in the warning dialog
4. Files are permanently deleted from disk and removed from the index

**Notes:**
- Sidecar files (`.photostat.json`) are automatically copied/moved/deleted with their images
- Move and delete operations update the search index automatically
- Deleted files cannot be recovered - use with caution!

---

## Finding Duplicates

PhotoStat can detect duplicate images using two methods:

- **Exact Duplicates** - Uses SHA-256 content hashing to find byte-for-byte identical files
- **Visual Duplicates** - Uses perceptual hashing (dHash) to find visually similar images, even if they've been resized, recompressed, or re-exported

### Prerequisites

Hashes are computed during indexing. To populate hash data for your collection:

1. Go to the **Index** tab
2. Click **Re-index All** to recompute hashes for existing images
3. New images indexed going forward will automatically have hashes computed

### Using the Duplicates Tab

1. Navigate to the **Duplicates** tab

2. **Choose a detection mode:**

   | Mode | Description | Best For |
   |------|-------------|----------|
   | **Exact Duplicates** | SHA-256 hash of file bytes | Finding identical copies across drives |
   | **Visual Duplicates** | Perceptual hash of image content | Finding resized, recompressed, or re-exported versions |

3. Click **Scan for Duplicates**

4. The summary shows:
   - Number of duplicate groups found
   - Total number of duplicate images
   - Reclaimable disk space (total size minus one copy per group)

5. **Browse duplicate groups** in the left panel:
   - Each group shows a representative filename and copy count
   - Total size and reclaimable space per group

6. **Review details** by clicking a group:
   - Thumbnail preview of each copy
   - Full file path, size, date taken, and dimensions
   - Checkboxes for selecting files to delete
   - **Double-click** any image to open it in your system's default viewer

7. **Delete duplicates:**
   - Check the copies you want to remove (you cannot select all files in a group)
   - Click **Delete Selected Files**
   - Confirm in the dialog
   - Files are permanently deleted from disk and removed from the index

**Tips:**
- Start with **Exact Duplicates** mode to find safe-to-remove identical copies
- Use **Visual Duplicates** to find images that may differ in resolution or compression
- Always review before deleting — visual duplicates may include intentionally different versions (e.g., edited vs. original)

### CLI Duplicate Finder

You can also find duplicates from the command line:

```bash
# Find exact duplicates (default)
java -jar photostat.jar --find-duplicates

# Find visual duplicates
java -jar photostat.jar --find-duplicates --mode visual

# Minimal output
java -jar photostat.jar --find-duplicates --quiet
```

The CLI outputs duplicate groups with file paths, sizes, dates, and reclaimable space.

---

## Exploring Charts

Navigate to the **Charts** tab to visualize your collection:

![Charts Tab](screenshots/charts-tab.png)

**Available Views:**

1. **Overview**
   - Camera makes bar chart
   - File types pie chart
   - Editing software bar chart

   ![Overview Charts](screenshots/charts-overview.png)

2. **Timeline**
   - Photos by month/year line chart
   - See your photography activity over time

   ![Timeline Chart](screenshots/charts-timeline.png)

3. **Exposure**
   - ISO distribution histogram
   - Aperture usage chart
   - Focal length distribution

   ![Exposure Charts](screenshots/charts-exposure.png)

4. **Locations** (if GPS data available)
   - Map plot of photo locations

---

## Thumbnail Cache

PhotoStat caches generated thumbnails to disk for faster loading on subsequent views.

**Configure Cache Settings:**

1. Open **File > Settings**
2. Navigate to the **Cache** tab
3. Configure options:

   | Setting | Description |
   |---------|-------------|
   | Enable Disk Cache | Turn disk caching on/off |
   | Max Cache Size | Maximum disk space for cache (100-5000 MB) |

4. View cache statistics (file count and size)
5. Click **Refresh Stats** to update the cache statistics
6. Click **Clear Cache** to remove all cached thumbnails
7. Click **Pre-cache Thumbnails** to generate thumbnails in advance

**Pre-caching Thumbnails:**

The Pre-cache Thumbnails feature generates thumbnails for all indexed images:

1. Click **Pre-cache Thumbnails** in the Cache settings
2. A progress dialog shows the caching progress
3. Statistics display: cached, skipped (already cached or unsupported), and failed counts
4. Caching stops automatically when the cache size limit is reached
5. Click **Cancel** to stop early, or **Close** when complete

Pre-caching improves the GUI experience by having thumbnails ready before you browse.

**CLI Pre-caching:**

You can also pre-cache thumbnails from the command line:

```bash
java -jar photostat.jar --cache-thumbnails
```

Options:
- `--parallel N` or `-p N` - Number of parallel threads (1-16, default: 4)
- `--dry-run` - Show what would be cached without actually caching
- `--quiet` or `-q` - Minimal output
- `--help` - Show help message

Examples:
```bash
# Use default 4 threads
java -jar photostat.jar --cache-thumbnails

# Use 8 threads for faster processing
java -jar photostat.jar --cache-thumbnails --parallel 8

# Use 2 threads with minimal output
java -jar photostat.jar --cache-thumbnails -p 2 --quiet
```

**Cache Location:** `~/.photostat/cache/`

**How It Works:**
- Thumbnails are saved as JPEG files with hashed filenames
- Cache key includes file path, modification time, and thumbnail size
- If source image is modified, a new thumbnail is generated automatically
- When cache exceeds max size, oldest thumbnails are removed (LRU eviction)

---

## Sidecar Files

Sidecar files allow custom metadata (persons, places, tags) to persist even when rebuilding the search index.

**How It Works:**
- When you save custom metadata, a `.photostat.json` file is created alongside the image
- Example: `IMG_1234.jpg` → `IMG_1234.jpg.photostat.json`
- When re-indexing, PhotoStat reads the sidecar and restores your custom metadata

**Example Sidecar File:**
```json
{
  "persons" : [ "John", "Jane" ],
  "place" : "Central Park",
  "tags" : [ "vacation", "family" ],
  "rating" : "****"
}
```

**Configure Sidecar Settings:**

1. Open **File > Settings**
2. Navigate to the **Indexing** tab
3. Toggle **"Save custom metadata to sidecar files"**

**Benefits:**
- Custom metadata survives index rebuilds
- Metadata travels with images if files are moved/copied
- Can be backed up alongside photos
- Human-readable JSON format

**Note:** If disabled, custom metadata is only stored in OpenSearch and will be lost if the index is deleted.

---

## Large Collections

If you have tens of thousands of images or more, here are some tips to keep PhotoStat running smoothly.

### Indexing Performance

Indexing computes both a **SHA-256 content hash** (for exact duplicate detection) and a **perceptual hash** (for visual duplicate detection) for every image. This hashing is the most time-consuming part of indexing, so initial indexing of a large collection can take a while.

**Increase indexing threads** to speed things up:

1. Open **Settings > Indexing**
2. Raise **Indexing Threads** (default is 4, try 8 or more if your system has the cores)
3. Click **OK** and start indexing

Or edit `~/.photostat/config.json` directly:

```json
{
  "indexing": {
    "indexing_threads": 8,
    "batch_size": 100
  }
}
```

Increasing `batch_size` (default 50) reduces the number of OpenSearch bulk requests, which can also help with large collections.

**Incremental indexing**: After the initial index, subsequent runs only process new or modified files. There's no need to re-index your entire collection each time.

### Managing Directories

Once a directory has been indexed, you can **remove it from the directory list** without losing the indexed data. The images remain searchable in OpenSearch. This is useful if you index photos from an external drive and then disconnect it — your metadata and search results are still available.

To add new images from that directory later, simply re-add it to the directory list and run indexing again. Only the new/changed files will be processed.

### Pre-cache Thumbnails via CLI

Generating thumbnails on the fly while browsing can feel sluggish with very large result sets. Pre-caching builds all thumbnails in advance so browsing is instant.

The CLI is the best way to pre-cache a large collection since it runs in the background without tying up the GUI:

```bash
# Pre-cache with default 4 threads
java -jar photostat-java-1.7.2-executable.jar --cache-thumbnails

# Use 8 threads for faster processing
java -jar photostat-java-1.7.2-executable.jar --cache-thumbnails --parallel 8

# Preview what would be cached
java -jar photostat-java-1.7.2-executable.jar --cache-thumbnails --dry-run
```

Already-cached thumbnails are skipped automatically, so you can re-run this after adding new images.

**Tip**: For very large collections, increase the cache size limit in **Settings > Cache** (default is 500 MB). A collection of 50,000 images typically needs around 2-3 GB of cache space.

### Batch AI Analysis via CLI

Analyzing thousands of images through the GUI is possible but the CLI is better suited for large batches — it supports parallel processing, runs in the background, and provides detailed progress output.

```bash
# Analyze all indexed images (skips already-analyzed ones)
java -jar photostat-java-1.7.2-executable.jar --analyze

# Run 4 analyses in parallel for faster throughput
java -jar photostat-java-1.7.2-executable.jar --analyze --parallel 4

# Use Gemini Flash for cheapest batch processing
java -jar photostat-java-1.7.2-executable.jar --analyze --provider gemini

# Preview what would be analyzed without making API calls
java -jar photostat-java-1.7.2-executable.jar --analyze --dry-run
```

**Cost awareness**: AI analysis incurs API costs per image. For very large collections, consider:
- Use `--dry-run` first to see how many images will be analyzed
- Start with **Gemini Flash** (~$0.05-0.10 per 1000 images) for cost-effective batch processing
- Use **Claude Sonnet** or **Haiku** for higher quality on a smaller subset
- Analysis results are cached — re-running skips unchanged images with no additional cost

Run it in the background on Linux/macOS:

```bash
nohup java -jar photostat-java-1.7.2-executable.jar --analyze --parallel 4 > analysis.log 2>&1 &
```

### RAW Files

RAW files (CR2, CR3, NEF, ARW, DNG, etc.) are significantly slower to index than JPEGs because they require ExifTool for metadata extraction and their embedded previews are larger. If you're doing an initial index of a mixed collection and want faster results:

1. Uncheck RAW extensions in **Settings > Indexing > File Types** to index JPEGs first
2. Run indexing for the fast pass
3. Re-enable RAW extensions and index again for the RAW files

### OpenSearch Tuning

For collections over 100,000 images, you may want to increase the OpenSearch Java heap size. If running via Docker:

```bash
docker run -d --name opensearch \
  -p 9200:9200 \
  -v opensearch-data:/usr/share/opensearch/data \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g" \
  opensearchproject/opensearch:2.11.0
```

The default heap is 512 MB which is fine for most collections, but bumping to 1-2 GB helps with heavy aggregation queries and large facet counts.

---

## Dark Theme

PhotoStat includes a dark theme for comfortable use in low-light environments.

### Switching Themes

1. Click **Settings** in the toolbar
2. Navigate to the **User Interface** tab
3. Select **Dark** or **Light** from the **Theme** dropdown
4. Click **OK** to save

The theme switches instantly — no restart required. Your preference is saved to the config file and persists across sessions.

### Theme Details

| Element | Light | Dark |
|---------|-------|------|
| Background | Light grey | Dark grey (#1e1e1e) |
| Panels | White | Dark surface (#2d2d2d) |
| Text | Dark (#333) | Light (#e0e0e0) |
| Accent | Blue (#0078d4) | Bright blue (#4ca6e8) |
| Selected rows | Light blue | Deep blue (#264f78) |

The dark theme covers all tabs (Search, Index, Duplicates, Charts), the Settings dialog, and all panels including facets, detail view, and file operations. The slideshow is unaffected as it always uses a black background.

---

## Enabling Logging

PhotoStat includes file-based logging for debugging:

1. **Edit the config file** at `~/.photostat/config.json`

2. **Add or modify the logging section:**

   ```json
   {
     "logging": {
       "enabled": true,
       "level": "INFO"
     }
   }
   ```

3. **Available log levels** (from most to least verbose):

   | Level | Description |
   |-------|-------------|
   | `DEBUG` | All messages including detailed debug info |
   | `INFO` | General information, warnings, and errors |
   | `WARN` | Warnings and errors only |
   | `ERROR` | Errors only |

4. **Log file location:** `~/.photostat/photostat.log`

5. **Restart the application** for changes to take effect

**Note:** Logging is disabled by default to avoid unnecessary disk writes.
