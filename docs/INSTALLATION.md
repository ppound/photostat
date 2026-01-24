# PhotoVault Installation Guide

This guide provides complete installation instructions for PhotoVault and all its dependencies.

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Installing Python](#installing-python)
3. [Installing ExifTool](#installing-exiftool)
4. [Installing OpenSearch](#installing-opensearch)
5. [Installing PhotoVault](#installing-photovault)
6. [Verifying Installation](#verifying-installation)
7. [Troubleshooting](#troubleshooting)

---

## System Requirements

- **Operating System**: Windows 10/11, Linux, or macOS
- **Python**: 3.11 or higher
- **RAM**: 4GB minimum (8GB recommended for large image collections)
- **Disk Space**:
  - ~500MB for OpenSearch
  - ~100MB for Python dependencies
  - Index storage varies by collection size (~1KB per image)

---

## Installing Python

### Windows

1. Download Python 3.11+ from [python.org](https://www.python.org/downloads/)
2. Run the installer
3. **Important**: Check "Add Python to PATH" during installation
4. Verify installation:
   ```cmd
   python --version
   ```

### Linux (Ubuntu/Debian)

```bash
sudo apt update
sudo apt install python3 python3-pip python3-venv
python3 --version
```

### WSL (Windows Subsystem for Linux)

```bash
sudo apt update
sudo apt install python3 python3-pip python3-venv python3-pyqt6
python3 --version
```

### macOS

```bash
# Using Homebrew
brew install python@3.11
python3 --version
```

---

## Installing ExifTool

ExifTool is required for extracting EXIF metadata from images.

### Windows

1. Download ExifTool from [exiftool.org](https://exiftool.org/)
2. Download the **Windows Executable** (`exiftool-XX.XX.zip`)
3. Extract the archive
4. Rename `exiftool(-k).exe` to `exiftool.exe`
5. Move `exiftool.exe` to a folder in your PATH, or add its location to PATH

**Option A - Add to PATH:**
1. Press `Win + X` → System → Advanced system settings
2. Click "Environment Variables"
3. Under "User variables", select "Path" → Edit
4. Add the folder containing `exiftool.exe`

**Option B - Place in Python Scripts folder:**
```cmd
# Find your Python Scripts folder
python -c "import sys; print(sys.prefix + '\\Scripts')"
# Copy exiftool.exe to that folder
```

### Linux (Ubuntu/Debian)

```bash
sudo apt install libimage-exiftool-perl
exiftool -ver
```

### WSL

```bash
sudo apt install libimage-exiftool-perl
exiftool -ver
```

### macOS

```bash
# Using Homebrew
brew install exiftool
exiftool -ver
```

### Manual Installation (All Platforms)

1. Download from [exiftool.org](https://exiftool.org/)
2. Extract to `~/.local/bin/` (Linux/macOS) or a folder in PATH (Windows)
3. Make executable (Linux/macOS):
   ```bash
   chmod +x ~/.local/bin/exiftool
   ```

---

## Installing OpenSearch

OpenSearch is the search engine that indexes and queries your image metadata.

### Option 1: Docker (Recommended)

The easiest way to run OpenSearch:

```bash
# Pull and run OpenSearch
docker run -d \
  --name opensearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin123!" \
  opensearchproject/opensearch:latest

# Verify it's running
curl http://localhost:9200
```

To stop/start OpenSearch:
```bash
docker stop opensearch
docker start opensearch
```

### Option 2: Docker Compose

Create `docker-compose.yml`:

```yaml
version: '3'
services:
  opensearch:
    image: opensearchproject/opensearch:latest
    container_name: opensearch
    environment:
      - discovery.type=single-node
      - DISABLE_SECURITY_PLUGIN=true
      - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
    volumes:
      - opensearch-data:/usr/share/opensearch/data

volumes:
  opensearch-data:
```

Run with:
```bash
docker-compose up -d
```

### Option 3: Manual Installation (Windows/Linux)

#### Step 1: Install Java (Required)

OpenSearch requires Java 11 or higher.

**Windows:**
1. Download OpenJDK from [adoptium.net](https://adoptium.net/)
2. Install and add to PATH

**Linux:**
```bash
sudo apt install openjdk-17-jdk
java -version
```

#### Step 2: Download OpenSearch

1. Go to [opensearch.org/downloads](https://opensearch.org/downloads.html)
2. Download the appropriate package:
   - Windows: ZIP archive
   - Linux: TAR.GZ archive

#### Step 3: Extract and Configure

**Windows:**
```cmd
# Extract to C:\opensearch
# Edit config\opensearch.yml:
```

**Linux:**
```bash
tar -xzf opensearch-*.tar.gz
cd opensearch-*
```

Edit `config/opensearch.yml`:
```yaml
# Disable security for local development
plugins.security.disabled: true

# Network settings
network.host: 127.0.0.1
http.port: 9200

# Single node
discovery.type: single-node
```

#### Step 4: Start OpenSearch

**Windows:**
```cmd
cd C:\opensearch
bin\opensearch.bat
```

**Linux:**
```bash
./bin/opensearch
```

#### Step 5: Verify OpenSearch is Running

```bash
curl http://localhost:9200
```

Expected response:
```json
{
  "name" : "your-node-name",
  "cluster_name" : "opensearch",
  "version" : {
    "distribution" : "opensearch",
    ...
  }
}
```

### Option 4: OpenSearch as a Windows Service

To run OpenSearch automatically on Windows startup:

1. Download [NSSM](https://nssm.cc/) (Non-Sucking Service Manager)
2. Run as Administrator:
   ```cmd
   nssm install OpenSearch C:\opensearch\bin\opensearch.bat
   nssm start OpenSearch
   ```

---

## Installing PhotoVault

### Step 1: Clone or Download

```bash
# Clone the repository
git clone <repository-url> photovault
cd photovault

# Or download and extract the ZIP file
```

### Step 2: Create Virtual Environment (Recommended)

```bash
# Create virtual environment
python3 -m venv venv

# Activate it
# Linux/macOS/WSL:
source venv/bin/activate

# Windows:
venv\Scripts\activate
```

### Step 3: Install Python Dependencies

```bash
pip install -r requirements.txt
```

**Requirements include:**
- `PyQt6>=6.5.0` - GUI framework
- `opensearch-py>=2.4.0` - OpenSearch client
- `pyexiftool>=0.5.0` - ExifTool wrapper
- `matplotlib>=3.7.0` - Charts and graphs

### Step 4: Run PhotoVault

```bash
python3 main.py
```

---

## Verifying Installation

### Check Python Dependencies

```bash
python3 -c "import PyQt6; print('PyQt6 OK')"
python3 -c "import opensearchpy; print('opensearch-py OK')"
python3 -c "import exiftool; print('pyexiftool OK')"
python3 -c "import matplotlib; print('matplotlib OK')"
```

### Check ExifTool

```bash
exiftool -ver
```
Should output version number (e.g., `12.70`)

### Check OpenSearch

```bash
curl http://localhost:9200
```
Should return JSON with cluster information

### Run PhotoVault

```bash
python3 main.py
```

The application should start and show "Connected" in the status bar.

---

## Troubleshooting

### "ExifTool not found" Error

**Cause:** ExifTool is not installed or not in PATH.

**Solutions:**
1. Install ExifTool following instructions above
2. Add ExifTool location to PATH
3. Place exiftool in `~/.local/bin/` (Linux/macOS)

### "Failed to connect to OpenSearch" Error

**Cause:** OpenSearch is not running or not accessible.

**Solutions:**
1. Verify OpenSearch is running:
   ```bash
   curl http://localhost:9200
   ```
2. Check if another service is using port 9200
3. Verify OpenSearch configuration in PhotoVault settings

### PyQt6 Display Issues on WSL

**Cause:** WSL needs a display server for GUI applications.

**Solutions:**

**WSL2 with WSLg (Windows 11):**
- Should work automatically

**WSL2 without WSLg:**
1. Install an X server on Windows (VcXsrv, X410, or Xming)
2. Configure display:
   ```bash
   export DISPLAY=$(cat /etc/resolv.conf | grep nameserver | awk '{print $2}'):0
   ```

### "ModuleNotFoundError" Errors

**Cause:** Python dependencies not installed.

**Solution:**
```bash
pip install -r requirements.txt
```

### OpenSearch Out of Memory

**Cause:** OpenSearch requires significant memory.

**Solution:** Limit heap size in `config/jvm.options`:
```
-Xms512m
-Xmx512m
```

### Permission Denied on Linux

**Cause:** File permission issues.

**Solution:**
```bash
chmod +x main.py
chmod -R 755 src/
```

---

## Updating PhotoVault

```bash
# Pull latest changes
git pull

# Update dependencies
pip install -r requirements.txt --upgrade

# Run the application
python3 main.py
```

---

## Uninstalling

### Remove PhotoVault
```bash
# Delete the photovault directory
rm -rf photovault/

# Or if using virtual environment, just delete the folder
```

### Remove OpenSearch (Docker)
```bash
docker stop opensearch
docker rm opensearch
docker volume rm opensearch-data
```

### Remove OpenSearch (Manual)
```bash
# Delete the opensearch directory
rm -rf opensearch/
```
