# Troubleshooting

This guide covers common issues and their solutions.

## Table of Contents

- [Application Won't Start](#application-wont-start)
  - [macOS: "PhotoStat is damaged" or "cannot be opened"](#macos-photostat-is-damaged-or-cannot-be-opened)
- [Can't Connect to OpenSearch](#cant-connect-to-opensearch)
- [Images Not Appearing](#images-not-appearing)
- [Thumbnails Not Showing](#thumbnails-not-showing)
- [Application Freezes](#application-freezes)
- [AI Analysis Issues](#ai-analysis-issues)
- [Face Recognition Issues](#face-recognition-issues)
  - [Python not available](#error-python-with-insightface-not-available)
  - [GPU not being used](#faces-tab-shows-available-gpu-but-detection-runs-on-cpu)
  - [Detection is slow](#face-detection-is-slow)
  - [Named person missing after re-scan](#named-person-not-appearing-in-search-after-re-scan)

---

## Application Won't Start

> **Using the MSI or DMG installer?** The native installers bundle their own Java runtime, so the "UnsupportedClassVersionError" and "JavaFX missing" errors below only apply to the cross-platform JAR.

### macOS: "PhotoStat is damaged" or "cannot be opened"

**Cause:** macOS Gatekeeper blocks apps that are not signed with an Apple developer certificate.

There are two ways to allow PhotoStat to run:

**Method 1 — Right-click to open (easiest, first launch only):**

1. In Finder, locate the **PhotoStat** app in your Applications folder
2. **Right-click** (or Control-click) the app icon and select **Open**
3. A dialog will appear saying the developer cannot be verified — click **Open**

macOS remembers this choice, so subsequent launches work normally by double-clicking.

**Method 2 — Privacy & Security settings (if Method 1 doesn't work):**

After attempting to open the app and seeing a "cannot be opened" or "damaged" warning:

1. Open **System Settings** (Apple menu → System Settings)
2. Navigate to **Privacy & Security**
3. Scroll down to the **Security** section
4. You should see a message like *"PhotoStat was blocked from use because it is not from an identified developer"*
5. Click **Open Anyway**
6. Authenticate with your password or Touch ID when prompted
7. Click **Open** in the final confirmation dialog

### Error: "UnsupportedClassVersionError"

**Cause:** You're using an older Java version.

**Solution:** Install Java 21 or later. Verify with:
```bash
java -version
```

### Error: "JavaFX runtime components are missing"

**Cause:** Using the wrong JAR file.

**Solution:** Use the `-executable.jar` file which includes JavaFX:
```bash
java -jar photostat-java-1.9.14-executable.jar
```

### Error on Intel Mac: "no suitable pipeline found" or graphics errors

**Cause:** The default executable JAR (`*-executable.jar`) includes Apple Silicon (ARM64) Mac natives, not Intel (x86_64) natives.

**Solution:** Download the Intel Mac JAR instead:

```bash
java -jar photostat-java-1.9.14-executable-mac-intel.jar
```

This JAR is built with `mvn package -Pmac-intel` and includes Intel Mac JavaFX natives alongside Windows and Linux natives.

If the Intel Mac JAR is not available for your version, you can download the JavaFX SDK for Mac x86_64 and run with the module path:

1. Download JavaFX SDK (x86_64) from https://gluonhq.com/products/javafx/
2. Extract to a folder
3. Run with:
```bash
java --module-path /path/to/javafx-sdk-21/lib \
     --add-modules javafx.controls,javafx.fxml,javafx.swing \
     -jar photostat-java-1.9.14-executable.jar
```

---

## Can't Connect to OpenSearch

### Error: "Connection refused"

**Possible causes:**
- OpenSearch is not running
- Wrong host or port configured

**Solutions:**

1. Verify OpenSearch is running:
   ```bash
   curl http://localhost:9200
   ```
   You should see a JSON response with cluster info.

2. Check settings in File > Settings:
   - Host: `localhost` (or your server IP)
   - Port: `9200` (default)

3. Check firewall settings if connecting to a remote server.

### Error: "Connection timed out"

**Possible causes:**
- OpenSearch is starting up
- Network issues
- Wrong port

**Solutions:**
- Wait a moment for OpenSearch to fully start
- Verify the port is correct
- Check network connectivity

### Error: "Authentication failed"

**Solutions:**
- Verify username and password in Settings
- Check if OpenSearch security plugin is enabled
- For development, you can disable security:
  ```bash
  docker run -e "DISABLE_SECURITY_PLUGIN=true" ...
  ```

---

## Images Not Appearing

### No results after indexing

**Solutions:**

1. Verify directories contain supported image formats
2. Check OpenSearch is running
3. Check the Index tab shows your directories
4. Try clicking "Re-index All"
5. Check the log file for errors (if logging enabled)

### RAW files not indexed

**Cause:** ExifTool is not installed or not in PATH.

**Solutions:**

1. Verify ExifTool is installed:
   ```bash
   exiftool -ver
   ```

2. If not installed, see the installation guide in README.

3. If installed but not found, set the full path in config.json:
   ```json
   {
     "exiftool": {
       "path": "/usr/bin/exiftool"
     }
   }
   ```

### Some images missing from results

**Possible causes:**
- File extension not in the supported list
- File permissions preventing access
- Corrupt image file

**Solutions:**
- Check if the file extension is supported
- Verify file permissions
- Try opening the image in another application

---

## Thumbnails Not Showing

### Blank thumbnails

**Possible causes:**
- Unsupported image format for preview
- Corrupt image file
- Cache issues

**Solutions:**
- For RAW files, ExifTool extracts embedded thumbnails
- Try clearing the cache via Settings > Cache > Clear Cache
- Check if the image opens in another application

### Slow thumbnail loading

**Possible causes:**
- First-time generation for large collection
- Cache disabled
- Low system memory

**Solutions:**
- Wait for initial cache population
- Ensure caching is enabled in Settings > Cache
- Increase available memory
- Cached thumbnails load faster on subsequent views

### Cache issues

**Solutions:**
1. Clear the cache: Settings > Cache > Clear Cache
2. Check available disk space
3. Verify cache location is writable: `~/.photostat/cache/`
4. Increase cache size if needed (Settings > Cache)

---

## Application Freezes

### UI becomes unresponsive

**Possible causes:**
- Large operation in progress (indexing, analysis)
- Low system memory
- Network timeout to OpenSearch

**Solutions:**
- Wait for current operation to complete
- Check system memory usage
- Indexing and analysis run in background threads, but very large batches may still impact UI
- Restart the application if completely frozen

### Freezes during indexing

**Solutions:**
- Large directories take time to scan
- Check available disk space
- Try indexing smaller directories first
- Enable logging to see progress

### Freezes during AI analysis

**Solutions:**
- AI analysis makes network calls which may timeout
- Use CLI mode for large batch processing
- Reduce batch size
- Check internet connection

---

## AI Analysis Issues

### Error: "API Key Required"

**Solution:** Configure your API key in Settings > AI Analysis tab.

### Error: "Invalid API key"

**Solutions:**
- Verify the key is correct (copy/paste again)
- Check the key is still active in provider console
- Ensure no extra spaces in the key

### Error: "API error: 429" (Rate Limited)

**Cause:** Too many requests to the API.

**Solutions:**
- Wait a few minutes before retrying
- Use fewer parallel threads (`--parallel 2` instead of `--parallel 8`)
- Process fewer images at once
- The CLI automatically retries with exponential backoff

### Error: "Analysis failed"

**Possible causes:**
- Network connection issue
- Unsupported image format
- Image too large

**Solutions:**
- Check internet connection
- Verify image format is JPG, PNG, GIF, or WebP
- RAW files cannot be analyzed directly
- Very large images may timeout

### Analysis returns empty or wrong results

**Solutions:**
- Try a different AI model
- Check the analysis prompt in config.json
- Some images may not have identifiable content
- Person detection describes, doesn't identify specific individuals

---

## Face Recognition Issues

For full setup instructions including GPU configuration, see [docs/FACE_RECOGNITION.md](FACE_RECOGNITION.md).

### Error: "Python with InsightFace not available"

**Cause:** Python or InsightFace is not installed.

**Solution:** Install the required Python packages:
```bash
# CPU only
pip install insightface onnxruntime scikit-learn

# GPU (NVIDIA CUDA) — never install both onnxruntime and onnxruntime-gpu
pip uninstall onnxruntime onnxruntime-gpu -y
pip install insightface onnxruntime-gpu scikit-learn
```

If Python is installed but not found, set the path in **Settings > Face Recognition** or edit `config.json`:
```json
{
  "faces": {
    "python_path": "/path/to/python3"
  }
}
```

### Faces tab shows "Available (GPU)" but detection runs on CPU

**Cause:** The "Available (GPU)" status only confirms the CUDA *driver* is detectable — it does not verify that GPU inference will actually work. Two common causes:

**Cause A: Both `onnxruntime` and `onnxruntime-gpu` are installed.** They share the same Python namespace and corrupt each other.

```powershell
pip uninstall onnxruntime onnxruntime-gpu -y
pip install onnxruntime-gpu
```

**Cause B: CUDA Toolkit 12.x and/or cuDNN not installed (Windows).** The GPU driver alone is not enough. `onnxruntime-gpu` requires both CUDA 12.x runtime DLLs (`cublasLt64_12.dll`, etc.) and cuDNN DLLs (`cudnn64_9.dll`, etc.). **CUDA 13.x is not compatible.** You must install CUDA 12.x even if CUDA 13.x is already present (multiple versions coexist safely).

1. Install CUDA Toolkit **12.x** (e.g. 12.6) from [developer.nvidia.com/cuda-12-6-0-download-archive](https://developer.nvidia.com/cuda-12-6-0-download-archive)
2. Add the CUDA 12.x `bin` directory to **System PATH** (not just user PATH):
   - Find installed versions: `dir "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA"`
   - Add: `C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.X\bin`
3. Install **cuDNN** from [developer.nvidia.com/cudnn-downloads](https://developer.nvidia.com/cudnn-downloads) — choose the **Tar** installer type, extract it, and copy all `.dll` files from its `bin\` folder into `C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.X\bin\`. See [docs/FACE_RECOGNITION.md — Windows GPU Setup](FACE_RECOGNITION.md#windows-gpu-setup) for detailed steps.

**Verify the fix** with this diagnostic command:
```powershell
python -c "import insightface, os; app = insightface.app.FaceAnalysis(name='buffalo_l', root=os.path.expanduser('~/.photostat/faces/models'), providers=['CUDAExecutionProvider','CPUExecutionProvider']); app.prepare(ctx_id=0, det_size=(640,640))"
```
You should see `Applied providers: ['CUDAExecutionProvider', 'CPUExecutionProvider']`. If it still shows CPU-only, look for errors like `cublasLt64_12.dll which is missing` or `Could not find module 'onnxruntime_providers_cuda.dll'` — both indicate CUDA 12.x bin is not in System PATH.

### Face detection is slow

**Possible causes:**
- Running on CPU instead of GPU (see above)
- Processing a large number of images

**Solutions:**
- Confirm GPU is actually being used with the diagnostic command above
- Use the CLI with parallel workers for large collections:
  ```bash
  java -jar photostat-java-1.9.14-executable.jar --detect-faces --parallel 4
  ```
- The InsightFace model (~350MB) downloads on first run — subsequent runs are faster

### Face clustering produces too many or too few clusters

**Solution:** Adjust the cluster threshold in **Settings > Face Recognition**:
- **Higher threshold** (e.g., 0.7–0.8): Stricter matching, more clusters, fewer false merges
- **Lower threshold** (e.g., 0.4–0.5): Looser matching, fewer clusters, may merge different people

You can also manually merge clusters in the Faces tab using the "Merge with..." button.

### Named person not appearing in search after re-scan

**Cause:** Face detection and clustering do not update the search index. Only clicking **Save Name** writes person names to OpenSearch and sidecar files. When new photos are added and re-scanned, the new images in a named cluster are not indexed until Save Name is clicked again.

**Solution:** After each re-scan, open every named cluster in the Faces tab and click **Save Name** to propagate the name to any newly added photos. The GUI displays a warning banner after each scan as a reminder.

### InsightFace model download fails

**Cause:** Network issue during first-run model download (~350MB).

**Solution:** The model is stored at `~/.photostat/faces/models/`. Delete any partial downloads and try again with a stable connection. The model is `buffalo_l` from InsightFace.

---

## Getting More Help

### Enable Logging

To diagnose issues, enable logging:

1. Edit `~/.photostat/config.json`:
   ```json
   {
     "logging": {
       "enabled": true,
       "level": "DEBUG"
     }
   }
   ```

2. Restart the application

3. Check `~/.photostat/photostat.log` for detailed error messages

### Report an Issue

If you can't resolve the issue:

1. Check existing issues: https://github.com/ppound/photostat/issues
2. Create a new issue with:
   - PhotoStat version
   - Java version (`java -version`)
   - Operating system
   - Steps to reproduce
   - Error messages or log excerpts
