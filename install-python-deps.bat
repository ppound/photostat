@echo off
REM
REM PhotoStat — Install Python Dependencies (Windows)
REM
REM Installs the optional Python packages needed for:
REM   - Face Recognition (insightface + onnxruntime)
REM   - Local AI Analysis (Moondream via transformers + torch)
REM
REM Usage:
REM   install-python-deps.bat              Install everything (CPU)
REM   install-python-deps.bat --gpu        Install with GPU/CUDA support
REM   install-python-deps.bat --faces      Face recognition only
REM   install-python-deps.bat --moondream  Moondream AI only

setlocal EnableDelayedExpansion

if defined PHOTOSTAT_PYTHON (
    set "PYTHON=%PHOTOSTAT_PYTHON%"
) else (
    set "PYTHON=python"
)

set "GPU=0"
set "INSTALL_FACES=1"
set "INSTALL_MOONDREAM=1"

:parse_args
if "%~1"=="" goto :check_python
if /I "%~1"=="--gpu"       ( set "GPU=1"            & shift & goto :parse_args )
if /I "%~1"=="--faces"     ( set "INSTALL_MOONDREAM=0" & shift & goto :parse_args )
if /I "%~1"=="--moondream" ( set "INSTALL_FACES=0"  & shift & goto :parse_args )
if /I "%~1"=="--help"      goto :show_help
if /I "%~1"=="-h"          goto :show_help
echo Unknown option: %~1 (try --help)
exit /b 1

:show_help
echo Usage: %~nx0 [--gpu] [--faces] [--moondream]
echo.
echo Options:
echo   --gpu        Install GPU-accelerated packages (NVIDIA CUDA required)
echo   --faces      Install face recognition dependencies only
echo   --moondream  Install Moondream local AI dependencies only
echo.
echo Environment:
echo   PHOTOSTAT_PYTHON   Python executable (default: python)
exit /b 0

:check_python
where %PYTHON% >nul 2>&1
if errorlevel 1 (
    echo Error: %PYTHON% not found. Install Python 3.10+ or set PHOTOSTAT_PYTHON.
    exit /b 1
)

for /f "delims=" %%v in ('%PYTHON% --version 2^>^&1') do set "PY_VERSION=%%v"
echo Using: %PYTHON% (%PY_VERSION%)
echo.

REM Upgrade pip
echo --- Upgrading pip ---
%PYTHON% -m pip install --upgrade pip
echo.

REM Face Recognition
if "%INSTALL_FACES%"=="1" (
    echo --- Installing Face Recognition dependencies ---
    if "%GPU%"=="1" (
        REM Remove CPU onnxruntime if present (conflicts with GPU version)
        %PYTHON% -m pip uninstall onnxruntime onnxruntime-gpu -y 2>nul
        %PYTHON% -m pip install insightface onnxruntime-gpu scikit-learn
    ) else (
        %PYTHON% -m pip install insightface onnxruntime scikit-learn
    )
    echo.
)

REM Moondream local AI
if "%INSTALL_MOONDREAM%"=="1" (
    echo --- Installing Moondream AI dependencies ---
    %PYTHON% -m pip install transformers==4.51.3 Pillow accelerate
    if "%GPU%"=="1" (
        echo --- Installing PyTorch with CUDA support ---
        %PYTHON% -m pip install torch --force-reinstall --index-url https://download.pytorch.org/whl/cu124
    ) else (
        %PYTHON% -m pip install torch
    )
    echo.
)

echo === Done ===
echo.

REM Verify
if "%INSTALL_FACES%"=="1" (
    echo Face Recognition:
    %PYTHON% -c "import insightface, onnxruntime; print(f'  insightface {insightface.__version__}, onnxruntime {onnxruntime.__version__}')" 2>nul || echo   WARNING: import failed — check errors above
)
if "%INSTALL_MOONDREAM%"=="1" (
    echo Moondream AI:
    %PYTHON% -c "import transformers, torch; print(f'  transformers {transformers.__version__}, torch {torch.__version__} (device: {chr(34)}cuda{chr(34)} if torch.cuda.is_available() else {chr(34)}cpu{chr(34)})')" 2>nul || echo   WARNING: import failed — check errors above
)

endlocal
