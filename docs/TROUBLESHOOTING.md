# Troubleshooting

This guide covers common issues and their solutions.

## Table of Contents

- [Application Won't Start](#application-wont-start)
  - [macOS: "PhotoStat is damaged" or "cannot be opened"](#macos-photostat-is-damaged-or-cannot-be-opened)
- [Backend Services and Docker](#backend-services-and-docker)
- [Can't Connect to OpenSearch](#cant-connect-to-opensearch)
  - [OpenSearch is read-only / flood-stage watermark](#opensearch-is-read-only--disk-usage-exceeded-flood-stage-watermark)
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
java -jar photostat-java-2.6.2-executable.jar
```

### Error on Intel Mac: "no suitable pipeline found" or graphics errors

**Cause:** The default executable JAR (`*-executable.jar`) includes Apple Silicon (ARM64) Mac natives, not Intel (x86_64) natives.

**Solution:** Download the Intel Mac JAR instead:

```bash
java -jar photostat-java-2.6.2-executable-mac-intel.jar
```

This JAR is built with `mvn package -Pmac-intel` and includes Intel Mac JavaFX natives alongside Windows and Linux natives.

If the Intel Mac JAR is not available for your version, you can download the JavaFX SDK for Mac x86_64 and run with the module path:

1. Download JavaFX SDK (x86_64) from https://gluonhq.com/products/javafx/
2. Extract to a folder
3. Run with:
```bash
java --module-path /path/to/javafx-sdk-21/lib \
     --add-modules javafx.controls,javafx.fxml,javafx.swing \
     -jar photostat-java-2.6.2-executable.jar
```

---

## Backend Services and Docker

The **Services** tab shows the state of the Docker engine and each backend
container. Most problems below show up there first.

### Services tab says "Docker engine: Not installed"

PhotoStat could not run `docker`. Either it isn't installed, or it isn't on the
`PATH` PhotoStat inherits.

- Click **Setup...** to run the wizard, which can install Docker Desktop for you.
- If Docker *is* installed, check `docker --version` works in a terminal. If it
  works there but not in PhotoStat, set the full path to the executable under
  the `docker.docker_path` key in `~/.photostat/config.json`.
- On Linux, PhotoStat cannot install Docker Engine for you — it needs root. Use
  your package manager, then reopen the wizard.

### Services tab says "Installed, but not running"

The Docker CLI is present but the daemon isn't responding. Click **Start
Docker**. A cold start takes 30–60 seconds, and Docker Desktop may ask you to
accept its own terms the first time.

If it never comes up:

- **Windows:** Docker Desktop may still need a restart to finish enabling WSL 2.
  PhotoStat will not restart your machine — restart when it suits you, then
  reopen the wizard.
- Check whether Docker Desktop starts on its own, outside PhotoStat. If it
  doesn't, the problem is with Docker rather than PhotoStat.

### Error: "A required port is already in use"

PhotoStat's services need 9200, 8001, 8002 and 8003. Something else already has
one of them — very often a previously created OpenSearch container from an
older setup.

```bash
# See what's using the ports
docker ps -a --format '{{.Names}}\t{{.Ports}}'

# Stop an old container that is holding 9200
docker stop opensearch
```

If you want to keep the other service, change the host-side port in
`~/.photostat/docker-compose.yml` (the number *before* the second colon) and
update the matching endpoint in **File > Settings**.

### A service is "running" but health stays "starting..."

Normal on first use. The AI containers download model weights the first time
they're exercised, which can take several minutes. Watch progress with:

```bash
docker compose -f ~/.photostat/docker-compose.yml logs -f faces
```

If it never becomes healthy, check that container's logs for an error.

### Error: "no matching manifest for linux/arm64/v8"

The images being pulled were built only for Intel/AMD processors, and this is an
Apple Silicon Mac. PhotoStat reports this as "The backend images are not
published for this computer's processor type".

PhotoStat 2.6.1 and earlier published amd64-only images. Later releases publish
the CPU images for both architectures, so upgrading is the real fix.

To run the Intel images under Rosetta emulation in the meantime, add a
`platform` line to each service in `~/.photostat/docker-compose.yml`:

```yaml
  faces:
    image: ghcr.io/ppound/photostat-faces:2.6.1-cpu
    platform: linux/amd64
```

Then **Stop All** and **Start All** from the Services tab. Expect the AI
features to be noticeably slower under emulation.

Note the GPU images are Intel-only by design — they are CUDA builds, and CUDA
does not exist on Apple Silicon. Macs should use the CPU profile.

### Images won't download

The pull needs network access to `ghcr.io`. On a corporate network a proxy or
firewall may block it. The images total several GB on CPU and considerably more
on GPU, so also check free disk space — Docker reports "no space left on
device" when it runs out.

### Changing the compose file has no effect

PhotoStat only rewrites `~/.photostat/docker-compose.yml` when it still matches
the previously shipped defaults, so your edits are safe. But an edited file also
stops receiving updates. Compare yours against `docker-compose.dist.yml` next to
it, which always holds the current defaults. To start over, delete your file and
relaunch PhotoStat.

Note that changes only take effect once the containers are recreated —
**Stop All** then **Start All** in the Services tab.

### Removing everything

Stop the services from the Services tab, then uninstall Docker Desktop the
normal way for your platform. To also reclaim the disk used by the images and
downloaded models:

```bash
docker compose -f ~/.photostat/docker-compose.yml down -v
```

The `-v` deletes the named volumes, which includes your OpenSearch index —
you'd need to re-index afterwards. Leave `-v` off to keep it.

---

## Can't Connect to OpenSearch

> If you're running OpenSearch as one of PhotoStat's own containers, check the
> **Services** tab first — the section above covers engine and container issues.

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

### OpenSearch is read-only / "disk usage exceeded flood-stage watermark"

Indexing fails while searching still works, and the error mentions a read-only
index or a flood-stage watermark. Older builds reported this unhelpfully as
`Indexing error: Forbidden access`.

OpenSearch stops accepting writes when the disk holding its data passes **95%**
full, and marks every index `read-only-allow-delete`. **It does not lift that
block by itself when space is freed** — you have to clear it.

**This is the disk OpenSearch runs on, not the drive holding your photos.** With
the Docker backend that is Docker's own disk image, which is a fixed size and
easy to fill with container images and model weights.

Check it:

```bash
docker exec photostat-opensearch-1 df -h /usr/share/opensearch/data
```

Free space — this only removes images no container is using:

```bash
docker system df          # see what is using the space
docker image prune -a
docker builder prune
```

Old images are the usual culprit: superseded versions left behind by upgrades,
and the GPU images if you ever selected that profile, which are 8-10 GB each.
Docker Desktop can also grow its disk under **Settings → Resources**.

Once you are back under 95%, clear the block:

```bash
curl -X PUT "http://localhost:9200/_all/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index.blocks.read_only_allow_delete": null}'
```

Indexing then works again. Clearing the block while still over the watermark
will not help — OpenSearch simply re-applies it.

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

### Stale entries: files renamed, moved, or deleted outside PhotoStat

**Symptom:** the index still shows entries for files that no longer exist at their stored paths — clicking them in results, opening their folder, or searching by old filename surfaces things that don't match what's on disk. Common causes: renaming files in Finder/Explorer, moving them between drives, or deleting them outside the app.

**Cause:** OpenSearch documents are keyed by the file's path. PhotoStat keeps the index in sync for operations done *inside* the app (Move, Rename, Delete in the results toolbar), but external changes aren't observed — a regular Re-index All adds the new paths as new documents and leaves the old ones behind as orphans.

**Solution:**

1. Go to the **Index** tab
2. Click **Re-index All**
3. In the confirmation dialog, check **"Also remove orphaned index entries"**
4. Click OK

After indexing completes, the orphan sweep deletes index entries whose files no longer exist on disk. The completion summary reports the count.

**Important — unmounted drives:** the sweep only operates on configured directories that are currently accessible. Any configured directory whose drive isn't mounted (or whose folder is missing) is skipped entirely and its entries are preserved. To do a full sweep across an external library, attach the drive first, then run Re-index All with the option checked. See the [Re-index All](USER_GUIDE.md#re-index-all) section of the User Guide for details.

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
  java -jar photostat-java-2.6.2-executable.jar --detect-faces --parallel 4
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
