#!/usr/bin/env bash
#
# PhotoStat — Install Python Dependencies
#
# Installs the optional Python packages needed for:
#   - Face Recognition (insightface + onnxruntime)
#   - Local AI Analysis (Moondream via transformers + torch)
#
# Usage:
#   ./install-python-deps.sh              # Install everything (CPU)
#   ./install-python-deps.sh --gpu        # Install with GPU/CUDA support
#   ./install-python-deps.sh --faces      # Face recognition only
#   ./install-python-deps.sh --moondream  # Moondream AI only

set -e

PYTHON="${PHOTOSTAT_PYTHON:-python3}"
GPU=false
INSTALL_FACES=true
INSTALL_MOONDREAM=true

for arg in "$@"; do
    case "$arg" in
        --gpu)        GPU=true ;;
        --faces)      INSTALL_MOONDREAM=false ;;
        --moondream)  INSTALL_FACES=false ;;
        --help|-h)
            echo "Usage: $0 [--gpu] [--faces] [--moondream]"
            echo ""
            echo "Options:"
            echo "  --gpu        Install GPU-accelerated packages (NVIDIA CUDA required)"
            echo "  --faces      Install face recognition dependencies only"
            echo "  --moondream  Install Moondream local AI dependencies only"
            echo ""
            echo "Environment:"
            echo "  PHOTOSTAT_PYTHON   Python executable (default: python3)"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg (try --help)"
            exit 1
            ;;
    esac
done

# Check Python is available
if ! command -v "$PYTHON" &>/dev/null; then
    echo "Error: $PYTHON not found. Install Python 3.10+ or set PHOTOSTAT_PYTHON."
    exit 1
fi

PY_VERSION=$("$PYTHON" --version 2>&1)
echo "Using: $PYTHON ($PY_VERSION)"
echo ""

# Upgrade pip
echo "--- Upgrading pip ---"
"$PYTHON" -m pip install --upgrade pip
echo ""

# Face Recognition
if $INSTALL_FACES; then
    echo "--- Installing Face Recognition dependencies ---"
    if $GPU; then
        # Remove CPU onnxruntime if present (conflicts with GPU version)
        "$PYTHON" -m pip uninstall onnxruntime onnxruntime-gpu -y 2>/dev/null || true
        "$PYTHON" -m pip install insightface onnxruntime-gpu scikit-learn
    else
        "$PYTHON" -m pip install insightface onnxruntime scikit-learn
    fi
    echo ""
fi

# Moondream local AI
if $INSTALL_MOONDREAM; then
    echo "--- Installing Moondream AI dependencies ---"
    "$PYTHON" -m pip install "transformers>=4.51,<5" Pillow accelerate
    if $GPU; then
        echo "--- Installing PyTorch with CUDA support ---"
        "$PYTHON" -m pip install torch --force-reinstall --index-url https://download.pytorch.org/whl/cu124
    else
        "$PYTHON" -m pip install torch
    fi
    echo ""
fi

echo "=== Done ==="
echo ""

# Verify
if $INSTALL_FACES; then
    echo "Face Recognition:"
    "$PYTHON" -c "import insightface, onnxruntime; print(f'  insightface {insightface.__version__}, onnxruntime {onnxruntime.__version__}')" 2>/dev/null \
        || echo "  WARNING: import failed — check errors above"
fi
if $INSTALL_MOONDREAM; then
    echo "Moondream AI:"
    "$PYTHON" -c "import transformers, torch; print(f'  transformers {transformers.__version__}, torch {torch.__version__} (device: {\"cuda\" if torch.cuda.is_available() else \"cpu\"})')" 2>/dev/null \
        || echo "  WARNING: import failed — check errors above"
fi
