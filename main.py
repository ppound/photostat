#!/usr/bin/env python3
"""PhotoStat - EXIF Image Search Desktop Application.

A Windows 11 desktop application that indexes EXIF metadata from image files
into OpenSearch and provides a searchable GUI with faceted results.

Prerequisites:
    1. Python 3.11+ installed
    2. ExifTool installed and in PATH (https://exiftool.org)
    3. OpenSearch instance running (local or remote)

Usage:
    python main.py

"""

from src.app import main

if __name__ == '__main__':
    main()
