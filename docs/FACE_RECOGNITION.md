# Face Recognition

PhotoStat can automatically detect faces in your photo collection, group similar faces into clusters, and let you assign person names that are saved to the search index and sidecar files.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
  - [CPU Installation](#cpu-installation)
  - [GPU Installation (Recommended)](#gpu-installation-recommended)
  - [Windows GPU Setup](#windows-gpu-setup)
  - [Verifying Your Installation](#verifying-your-installation)
- [Using Face Recognition (GUI)](#using-face-recognition-gui)
  - [Scanning for Faces](#scanning-for-faces)
  - [Understanding Clusters](#understanding-clusters)
  - [Naming a Cluster](#naming-a-cluster)
  - [Re-scanning After Adding Photos](#re-scanning-after-adding-photos)
  - [Merging Clusters](#merging-clusters)
- [Command-Line Interface](#command-line-interface)
  - [Basic Usage](#basic-usage)
  - [CLI Options](#cli-options)
  - [Examples](#examples)
- [How Incremental Scanning Works](#how-incremental-scanning-works)
- [Performance Notes](#performance-notes)
- [Installing Python 3](#installing-python-3)

---

## Overview

Face recognition in PhotoStat is powered by [InsightFace](https://github.com/deepinsight/insightface), a Python library that runs as a sidecar process. PhotoStat handles the Java/UI side; InsightFace handles the actual model inference. The detection model (`buffalo_l`, ~350 MB) downloads automatically on first use.

The workflow is:

1. **Detect** — find all faces in your indexed images
2. **Cluster** — group similar faces together automatically
3. **Name** — assign a person name to each cluster
4. **Save** — write the name to the search index and sidecar files

---

## Prerequisites

- **Python 3.8+** in your system PATH (or configured in Settings > Face Recognition)
- **pip** for installing packages
- **NVIDIA GPU + CUDA Toolkit** (optional but strongly recommended — see below)

If Python is not installed, see [Installing Python 3](#installing-python-3) at the bottom of this guide.

---

## Installation

### CPU Installation

Suitable for small collections or systems without an NVIDIA GPU:

```bash
pip install insightface onnxruntime scikit-learn
```

> `scikit-learn` is optional but improves clustering quality (enables DBSCAN).

### GPU Installation (Recommended)

**Important:** `onnxruntime` (CPU) and `onnxruntime-gpu` are mutually exclusive. Never have both installed at the same time — they share the same Python package namespace and will break each other.

If you previously installed `onnxruntime`, remove it first:

```bash
pip uninstall onnxruntime onnxruntime-gpu -y
pip install insightface onnxruntime-gpu scikit-learn
```

### Windows GPU Setup

GPU inference on Windows requires both `onnxruntime-gpu` **and** the NVIDIA CUDA Toolkit. The GPU driver alone is not sufficient — the toolkit provides the runtime DLLs (`cublasLt64_12.dll`, etc.) that onnxruntime needs at model-load time.

**Step 1 — Install NVIDIA CUDA Toolkit 12.x**

`onnxruntime-gpu` is built against **CUDA 12.x** and links against DLLs named `cublasLt64_**12**.dll`. CUDA 13.x ships differently-named DLLs and is **not yet supported** by onnxruntime-gpu — if you have CUDA 13.x installed, you still need to install CUDA 12.x alongside it. Multiple CUDA versions coexist safely.

Download CUDA 12.6 (recommended) from [developer.nvidia.com/cuda-12-6-0-download-archive](https://developer.nvidia.com/cuda-12-6-0-download-archive).

**Step 2 — Add CUDA to your system PATH**

The CUDA `bin` directory must be in the **System** PATH (not just user PATH, which is not inherited by applications launched via double-click or file association).

1. Open **Start → Search → "Edit the system environment variables"**
2. Click **Environment Variables**
3. Under **System variables**, select **Path** and click **Edit**
4. Add the **CUDA 12.x** bin directory — replace `v12.X` with your 12.x version (e.g. `v12.6`):
   ```
   C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.X\bin
   ```
   To see installed versions: `dir "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA"`
   If you have CUDA 13.x installed, make sure the **12.x** bin directory is also listed here.
5. Click **OK** on all dialogs

**Step 3 — Install cuDNN**

cuDNN provides the deep neural network primitives that onnxruntime-gpu uses during face detection. Without it, GPU inference will fall back to CPU even when the CUDA Toolkit is installed.

1. Go to [developer.nvidia.com/cudnn-downloads](https://developer.nvidia.com/cudnn-downloads)
2. Select your OS (**Windows**), architecture (**x86_64**), and choose the version that matches your CUDA 12.x install
3. Under **Installer Type**, choose **Tar** (the tarball — not the exe installer, which targets a different location)
4. Download and extract the archive — you will get a folder containing `bin\`, `include\`, and `lib\` subdirectories

5. Copy the DLLs from the extracted `bin\` folder into your CUDA 12.x `bin` directory:
   ```
   # Source (inside the extracted cuDNN archive):
   cudnn-windows-x86_64-...\bin\cudnn64_9.dll
   cudnn-windows-x86_64-...\bin\cudnn_ops64_9.dll
   cudnn-windows-x86_64-...\bin\cudnn_cnn64_9.dll
   (and any other .dll files in the bin\ folder)

   # Destination:
   C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.6\bin\
   ```

   > The exact DLL names depend on the cuDNN version (e.g. `cudnn64_9.dll` for cuDNN 9.x). Copy all `.dll` files from the cuDNN `bin\` folder to the CUDA `bin\` directory.

6. No PATH change is needed — the DLLs are now in the CUDA `bin` directory that is already in System PATH.

**Step 5 — Verify there is no `onnxruntime` conflict**

```powershell
pip show onnxruntime onnxruntime-gpu
```

You should see only `onnxruntime-gpu`. If both appear, run:

```powershell
pip uninstall onnxruntime onnxruntime-gpu -y
pip install onnxruntime-gpu
```

### Verifying Your Installation

Run this command to confirm GPU inference actually works (not just that the driver is present):

```powershell
python -c "import insightface, os; app = insightface.app.FaceAnalysis(name='buffalo_l', root=os.path.expanduser('~/.photostat/faces/models'), providers=['CUDAExecutionProvider','CPUExecutionProvider']); app.prepare(ctx_id=0, det_size=(640,640))"
```

**GPU working:** output includes `Applied providers: ['CUDAExecutionProvider', 'CPUExecutionProvider']`

**GPU not working:** output includes `Applied providers: ['CPUExecutionProvider']` and an error such as:
```
Error loading "onnxruntime_providers_cuda.dll" which depends on "cublasLt64_12.dll" which is missing.
```
or:
```
Could not find module 'C:\...\onnxruntime_providers_cuda.dll'
```
Both mean CUDA 12.x is not installed or its `bin` directory is not in PATH. CUDA 13.x alone is not sufficient — onnxruntime-gpu requires CUDA 12.x specifically. See [Windows GPU Setup](#windows-gpu-setup) above.

> **Note:** The **Faces tab** in the GUI shows "Available (GPU)" based on whether `onnxruntime` can *see* the CUDA driver — not whether GPU inference will actually succeed. The verification command above is the reliable test.

### Running via Docker (no local Python)

Instead of installing Python, InsightFace, and onnxruntime yourself, you can run
face detection as a container and have PhotoStat talk to it over HTTP — the
easiest path to a clean, optionally GPU-accelerated setup.

1. Start the faces service (see [`docker/README.md`](../docker/README.md)):
   ```bash
   cd docker
   docker compose up -d faces               # CPU
   # or, with an NVIDIA GPU + Container Toolkit:
   docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d faces
   ```
2. In **Settings → Face Recognition**, set **Backend** to **Docker (HTTP)** and
   point **Docker Endpoint** at the service (default `http://localhost:8001`).
3. Click **Check** — it should report the loaded providers (`CUDAExecutionProvider`
   for GPU, otherwise CPU).

Images are sent to the container as bytes, so it needs no access to your photo
files. Scanning and clustering behave identically to local mode.

---

## Using Face Recognition (GUI)

![Faces Tab](screenshots/faces-tab.png)

### Scanning for Faces

1. Open the **Faces** tab
2. Verify Python status shows **Available (GPU)** or **Available (CPU)**
3. Click **Scan for Faces**

PhotoStat will:
- Fetch all JPG/PNG paths from your OpenSearch index
- Detect faces in any images not previously scanned (incremental)
- Cluster similar faces together
- Display the resulting clusters in the left panel

Scanning is incremental — only images not previously processed are scanned. Progress is saved after every 500 images so it is safe to interrupt and resume.

### Understanding Clusters

Each cluster in the left panel represents a group of faces that the model believes belong to the same person. The panel shows:
- A representative face thumbnail
- The assigned name (if any)
- Face count and photo count

Clusters are sorted by size (most faces first).

### Naming a Cluster

1. Click a cluster in the left panel
2. Type the person's name in the **Person Name** field
3. Click **Save Name**

Saving a name updates every photo in that cluster in the OpenSearch index and writes a sidecar file alongside each image. A progress bar shows the update status.

### Re-scanning After Adding Photos

When you add new photos and re-scan:

- New faces are detected and added to the existing clusters
- **The search index is not updated automatically** — newly added photos in a named cluster do not inherit the person name until you click Save Name again

After each re-scan, a warning banner appears if any named clusters exist:

> ⚠ Named clusters may contain new photos from this scan. Open each named cluster and click 'Save Name' to update the search index with the new images.

Open each named cluster and click **Save Name** to propagate the name to the new photos.

### Merging Clusters

If the same person appears in multiple clusters:

1. Click the cluster you want to keep
2. Click **Merge with...**
3. Select the cluster to merge into it

The selected cluster absorbs all faces from the other cluster. After merging, click **Save Name** to update the index.

---

## Command-Line Interface

### Basic Usage

```bash
java -jar photostat-java-2.4.0-executable.jar --detect-faces
```

This fetches image paths from OpenSearch, detects faces in any new images, clusters all faces, and prints a summary.

### CLI Options

| Option | Description |
|--------|-------------|
| `--dir <path>` | Scan a directory directly instead of using OpenSearch |
| `--parallel <n>` | Run `n` parallel Python workers (1–8, default 1) |
| `--detect-only` | Run detection only, skip clustering |
| `--cluster-only` | Re-cluster existing face data, skip detection |
| `--force` | Rescan all images, ignoring previous results |
| `--dry-run` | Show what would be processed without running |
| `--quiet` / `-q` | Minimal output |
| `--no-progress` | Disable progress line updates |

### Examples

```bash
# Standard incremental scan
java -jar photostat-java-2.4.0-executable.jar --detect-faces

# Parallel scan with 4 workers (faster on GPU systems)
java -jar photostat-java-2.4.0-executable.jar --detect-faces --parallel 4

# Scan a specific directory
java -jar photostat-java-2.4.0-executable.jar --detect-faces --dir /Volumes/Photos/2024

# Re-cluster without re-scanning (useful after adjusting threshold)
java -jar photostat-java-2.4.0-executable.jar --detect-faces --cluster-only
```

> **After a CLI scan:** If named clusters exist, the CLI prints a reminder that you need to open the GUI Faces tab and click **Save Name** on each named cluster to update the search index for any new photos.

---

## How Incremental Scanning Works

PhotoStat tracks every image that has been through the detection pipeline — including images where no faces were found — in `~/.photostat/faces/scanned_paths.json`. On re-run, these images are skipped automatically.

The face detection data is stored in:

| File | Contents |
|------|----------|
| `~/.photostat/faces/face_data.json` | All detected face bounding boxes and embeddings |
| `~/.photostat/faces/clusters.json` | Cluster assignments and person names |
| `~/.photostat/faces/scanned_paths.json` | Every image that has been scanned (including face-free images) |

Progress is saved after every 500 images during scanning, so it is safe to interrupt a long scan and resume it later.

---

## Performance Notes

| Setup | Typical speed |
|-------|---------------|
| CPU only | 1–5 seconds/image |
| NVIDIA GPU (CUDA) | 0.05–0.2 seconds/image |
| CLI `--parallel 4` (GPU) | ~4× GPU speed across workers |

For large collections (10,000+ images), GPU acceleration is strongly recommended. A 10,000-image collection takes roughly 15–30 minutes on CPU vs. 10–20 minutes on a mid-range GPU with parallel workers.

The InsightFace `buffalo_l` model (~350 MB) is downloaded automatically to `~/.photostat/faces/models/` on first use. Subsequent runs reuse the cached model.

---

## Installing Python 3

### Windows

**Option A — Microsoft Store (easiest)**

1. Open the **Microsoft Store**, search for **Python 3**, and install the latest 3.x release.
2. Open a new PowerShell window and verify:
   ```powershell
   python --version
   pip --version
   ```

**Option B — python.org installer**

1. Download the installer from [python.org/downloads](https://www.python.org/downloads/)
2. Run the installer and **check "Add Python to PATH"** before clicking Install.
3. Open a new PowerShell window and verify:
   ```powershell
   python --version
   pip --version
   ```

> **Important:** Always open a **new** terminal window after installation so the updated PATH takes effect.

**If pip is not available after installing Python:**
```powershell
python -m ensurepip --upgrade
python -m pip install --upgrade pip
```

If PhotoStat cannot find Python automatically, set the full path in **Settings > Face Recognition** (e.g. `C:\Users\YourName\AppData\Local\Programs\Python\Python312\python.exe`).

---

### macOS

macOS does not include a modern Python by default. Install via Homebrew (recommended) or python.org.

**Homebrew:**
```bash
brew install python
```

**python.org installer:**

Download from [python.org/downloads](https://www.python.org/downloads/macos/) and run the `.pkg` installer.

Verify:
```bash
python3 --version
pip3 --version
```

On macOS, the command is `python3` (not `python`). pip3 is included with both the Homebrew and python.org installs. If it is missing:
```bash
python3 -m ensurepip --upgrade
python3 -m pip install --upgrade pip
```

Set the Python path in **Settings > Face Recognition** to the output of:
```bash
which python3
```

---

### Linux

**Ubuntu / Debian:**
```bash
sudo apt update
sudo apt install python3 python3-pip
```

**Fedora / RHEL:**
```bash
sudo dnf install python3 python3-pip
```

Verify:
```bash
python3 --version
pip3 --version
```

On some minimal Linux installations pip may not be included even after installing `python3`. If `pip3 --version` fails, install it separately:

**Ubuntu / Debian:**
```bash
sudo apt install python3-pip
```

**Fedora / RHEL:**
```bash
sudo dnf install python3-pip
```

Or use Python's built-in bootstrap (works on any distro):
```bash
python3 -m ensurepip --upgrade
```

On Linux, set the Python path in **Settings > Face Recognition** to `/usr/bin/python3` if PhotoStat does not detect it automatically.
