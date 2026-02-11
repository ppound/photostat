# Troubleshooting

This guide covers common issues and their solutions.

## Table of Contents

- [Application Won't Start](#application-wont-start)
- [Can't Connect to OpenSearch](#cant-connect-to-opensearch)
- [Images Not Appearing](#images-not-appearing)
- [Thumbnails Not Showing](#thumbnails-not-showing)
- [Application Freezes](#application-freezes)
- [AI Analysis Issues](#ai-analysis-issues)

---

## Application Won't Start

> **Using the MSI or DMG installer?** The native installers bundle their own Java runtime, so the "UnsupportedClassVersionError" and "JavaFX missing" errors below only apply to the cross-platform JAR.

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
java -jar photostat-java-1.6.4-executable.jar
```

### Error on Apple Silicon Mac: "no suitable pipeline found" or graphics errors

**Cause:** The executable JAR includes Intel Mac natives, not ARM64.

**Solution:** Download JavaFX SDK for Mac ARM64 and run with module path:

1. Download JavaFX SDK from https://gluonhq.com/products/javafx/
2. Extract to a folder
3. Run with:
```bash
java --module-path /path/to/javafx-sdk-21/lib \
     --add-modules javafx.controls,javafx.fxml,javafx.swing \
     -jar photostat-java-1.6.4-executable.jar
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
