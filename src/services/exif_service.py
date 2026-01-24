"""EXIF extraction service using ExifTool."""

import os
import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Dict, Any

import exiftool

from ..models.image_metadata import ImageMetadata


def find_exiftool() -> Optional[str]:
    """Find exiftool executable in common locations."""
    # Common locations to search
    search_paths = [
        # User's local bin (where we installed it)
        os.path.expanduser("~/.local/bin/exiftool"),
        # Common Linux locations
        "/usr/bin/exiftool",
        "/usr/local/bin/exiftool",
        "/opt/exiftool/exiftool",
        # Temp location (from our installation)
        "/tmp/exiftool-12.70/exiftool",
        "/tmp/Image-ExifTool-12.70/exiftool",
    ]

    # First check if it's in PATH
    exiftool_in_path = shutil.which("exiftool")
    if exiftool_in_path:
        return exiftool_in_path

    # Check common locations
    for path in search_paths:
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path

    # Check if there's an exiftool directory in /tmp
    tmp_path = Path("/tmp")
    if tmp_path.exists():
        for item in tmp_path.iterdir():
            if item.is_dir() and "exiftool" in item.name.lower():
                exiftool_path = item / "exiftool"
                if exiftool_path.is_file():
                    return str(exiftool_path)

    return None


class ExifService:
    """Service for extracting EXIF metadata from image files using ExifTool."""

    SUPPORTED_EXTENSIONS = {
        '.jpg', '.jpeg', '.png', '.tiff', '.tif', '.gif', '.bmp',
        '.raw', '.cr2', '.cr3', '.nef', '.arw', '.orf', '.rw2',
        '.dng', '.pef', '.srw', '.raf', '.heic', '.heif', '.webp'
    }

    # Class variable to cache exiftool path
    _exiftool_path: Optional[str] = None

    def __init__(self, exiftool_path: Optional[str] = None):
        """Initialize the EXIF service.

        Args:
            exiftool_path: Optional path to exiftool executable. If not provided,
                          will search common locations.
        """
        self._et = None

        # Find exiftool if not provided
        if exiftool_path:
            self._exiftool_path = exiftool_path
        elif ExifService._exiftool_path is None:
            ExifService._exiftool_path = find_exiftool()

    @classmethod
    def get_exiftool_path(cls) -> Optional[str]:
        """Get the path to exiftool executable."""
        if cls._exiftool_path is None:
            cls._exiftool_path = find_exiftool()
        return cls._exiftool_path

    @classmethod
    def is_exiftool_available(cls) -> bool:
        """Check if exiftool is available."""
        return cls.get_exiftool_path() is not None

    def __enter__(self):
        """Start ExifTool process."""
        exiftool_path = self.get_exiftool_path()

        if not exiftool_path:
            raise RuntimeError(
                "ExifTool not found. Please install ExifTool:\n"
                "  - Download from https://exiftool.org\n"
                "  - Or install via package manager (apt install libimage-exiftool-perl)\n"
                "  - Or place exiftool in ~/.local/bin/"
            )

        self._et = exiftool.ExifToolHelper(executable=exiftool_path)
        self._et.__enter__()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Stop ExifTool process."""
        if self._et:
            self._et.__exit__(exc_type, exc_val, exc_tb)
            self._et = None

    def is_supported_file(self, file_path: str) -> bool:
        """Check if a file is a supported image format."""
        ext = Path(file_path).suffix.lower()
        return ext in self.SUPPORTED_EXTENSIONS

    def scan_directory(self, directory: str, recursive: bool = True) -> List[str]:
        """Scan a directory for supported image files."""
        image_files = []
        path = Path(directory)

        if not path.exists() or not path.is_dir():
            return image_files

        if recursive:
            for file_path in path.rglob('*'):
                if file_path.is_file() and self.is_supported_file(str(file_path)):
                    image_files.append(str(file_path))
        else:
            for file_path in path.iterdir():
                if file_path.is_file() and self.is_supported_file(str(file_path)):
                    image_files.append(str(file_path))

        return image_files

    def extract_metadata(self, file_path: str) -> Optional[ImageMetadata]:
        """Extract EXIF metadata from a single image file."""
        if not self._et:
            raise RuntimeError("ExifService must be used as a context manager")

        if not os.path.exists(file_path):
            return None

        try:
            metadata_list = self._et.get_metadata(file_path)
            if not metadata_list:
                return None

            raw_metadata = metadata_list[0]
            return self._parse_metadata(file_path, raw_metadata)

        except Exception as e:
            print(f"Error extracting metadata from {file_path}: {e}")
            return None

    def extract_metadata_batch(self, file_paths: List[str]) -> List[ImageMetadata]:
        """Extract EXIF metadata from multiple image files."""
        if not self._et:
            raise RuntimeError("ExifService must be used as a context manager")

        results = []
        existing_paths = [p for p in file_paths if os.path.exists(p)]

        if not existing_paths:
            return results

        try:
            metadata_list = self._et.get_metadata(existing_paths)
            for i, raw_metadata in enumerate(metadata_list):
                parsed = self._parse_metadata(existing_paths[i], raw_metadata)
                if parsed:
                    results.append(parsed)

        except Exception as e:
            print(f"Error extracting batch metadata: {e}")
            # Fall back to individual extraction
            for file_path in existing_paths:
                try:
                    parsed = self.extract_metadata(file_path)
                    if parsed:
                        results.append(parsed)
                except Exception:
                    pass

        return results

    def _parse_metadata(self, file_path: str, raw: Dict[str, Any]) -> ImageMetadata:
        """Parse raw ExifTool output into ImageMetadata."""
        path = Path(file_path)
        stat = path.stat()

        metadata = ImageMetadata(
            file_path=str(path.resolve()),
            file_name=path.name,
            file_size=stat.st_size,
            file_type=path.suffix.lower().lstrip('.'),
            date_indexed=datetime.now(),
            all_exif=raw
        )

        # Parse date taken
        date_taken = self._get_first_value(raw, [
            'EXIF:DateTimeOriginal',
            'EXIF:CreateDate',
            'XMP:DateTimeOriginal',
            'XMP:CreateDate',
            'QuickTime:CreateDate',
            'IPTC:DateCreated'
        ])
        if date_taken:
            metadata.date_taken = self._parse_exif_date(date_taken)

        # Camera information
        metadata.camera_make = self._clean_string(self._get_first_value(raw, [
            'EXIF:Make', 'XMP:Make'
        ]))
        metadata.camera_model = self._clean_string(self._get_first_value(raw, [
            'EXIF:Model', 'XMP:Model'
        ]))
        metadata.lens_model = self._clean_string(self._get_first_value(raw, [
            'EXIF:LensModel', 'XMP:Lens', 'EXIF:LensInfo',
            'Composite:LensID', 'MakerNotes:LensType'
        ]))

        # Exposure settings
        focal_length = self._get_first_value(raw, [
            'EXIF:FocalLength', 'Composite:FocalLength35efl'
        ])
        if focal_length:
            metadata.focal_length = self._parse_focal_length(focal_length)

        aperture = self._get_first_value(raw, [
            'EXIF:FNumber', 'EXIF:ApertureValue', 'Composite:Aperture'
        ])
        if aperture:
            metadata.aperture = self._parse_aperture(aperture)

        metadata.shutter_speed = self._get_first_value(raw, [
            'EXIF:ExposureTime', 'EXIF:ShutterSpeedValue', 'Composite:ShutterSpeed'
        ])
        if metadata.shutter_speed and isinstance(metadata.shutter_speed, (int, float)):
            metadata.shutter_speed = self._format_shutter_speed(metadata.shutter_speed)

        iso = self._get_first_value(raw, [
            'EXIF:ISO', 'EXIF:ISOSpeedRatings', 'Composite:ISO'
        ])
        if iso:
            metadata.iso = self._parse_int(iso)

        metadata.exposure_mode = self._get_first_value(raw, [
            'EXIF:ExposureMode', 'EXIF:ExposureProgram'
        ])
        metadata.white_balance = self._get_first_value(raw, [
            'EXIF:WhiteBalance'
        ])
        metadata.flash = self._get_first_value(raw, [
            'EXIF:Flash', 'Composite:Flash'
        ])

        # Image dimensions
        width = self._get_first_value(raw, [
            'EXIF:ImageWidth', 'EXIF:ExifImageWidth', 'File:ImageWidth'
        ])
        if width:
            metadata.image_width = self._parse_int(width)

        height = self._get_first_value(raw, [
            'EXIF:ImageHeight', 'EXIF:ExifImageHeight', 'File:ImageHeight'
        ])
        if height:
            metadata.image_height = self._parse_int(height)

        metadata.orientation = self._get_first_value(raw, ['EXIF:Orientation'])

        # GPS data
        gps_lat = self._get_first_value(raw, ['EXIF:GPSLatitude', 'Composite:GPSLatitude'])
        gps_lon = self._get_first_value(raw, ['EXIF:GPSLongitude', 'Composite:GPSLongitude'])
        gps_lat_ref = self._get_first_value(raw, ['EXIF:GPSLatitudeRef'])
        gps_lon_ref = self._get_first_value(raw, ['EXIF:GPSLongitudeRef'])

        if gps_lat is not None and gps_lon is not None:
            metadata.gps_latitude = self._parse_gps_coordinate(gps_lat, gps_lat_ref)
            metadata.gps_longitude = self._parse_gps_coordinate(gps_lon, gps_lon_ref)

        # Other metadata
        metadata.artist = self._clean_string(self._get_first_value(raw, [
            'EXIF:Artist', 'XMP:Creator', 'IPTC:By-line'
        ]))
        metadata.copyright = self._clean_string(self._get_first_value(raw, [
            'EXIF:Copyright', 'XMP:Rights', 'IPTC:CopyrightNotice'
        ]))
        metadata.software = self._clean_string(self._get_first_value(raw, [
            'EXIF:Software', 'XMP:CreatorTool'
        ]))

        return metadata

    def _get_first_value(self, data: Dict[str, Any], keys: List[str]) -> Optional[Any]:
        """Get the first non-None value from a list of keys."""
        for key in keys:
            if key in data and data[key] is not None:
                return data[key]
        return None

    def _clean_string(self, value: Optional[Any]) -> Optional[str]:
        """Clean and return a string value."""
        if value is None:
            return None
        s = str(value).strip()
        return s if s else None

    def _parse_exif_date(self, date_str: Any) -> Optional[datetime]:
        """Parse EXIF date string to datetime."""
        if date_str is None:
            return None

        date_str = str(date_str).strip()

        # Common EXIF date formats
        formats = [
            '%Y:%m:%d %H:%M:%S',
            '%Y-%m-%d %H:%M:%S',
            '%Y:%m:%d %H:%M:%S%z',
            '%Y-%m-%dT%H:%M:%S',
            '%Y-%m-%dT%H:%M:%S%z',
            '%Y-%m-%dT%H:%M:%S.%f',
            '%Y-%m-%dT%H:%M:%S.%f%z',
        ]

        for fmt in formats:
            try:
                return datetime.strptime(date_str, fmt)
            except ValueError:
                continue

        return None

    def _parse_focal_length(self, value: Any) -> Optional[float]:
        """Parse focal length value."""
        if value is None:
            return None

        if isinstance(value, (int, float)):
            return float(value)

        s = str(value)
        # Extract number from strings like "50.0 mm"
        match = re.search(r'([\d.]+)', s)
        if match:
            try:
                return float(match.group(1))
            except ValueError:
                pass
        return None

    def _parse_aperture(self, value: Any) -> Optional[float]:
        """Parse aperture value."""
        if value is None:
            return None

        if isinstance(value, (int, float)):
            return float(value)

        s = str(value)
        # Extract number from strings like "f/2.8" or "2.8"
        match = re.search(r'f?/?(\d+\.?\d*)', s, re.IGNORECASE)
        if match:
            try:
                return float(match.group(1))
            except ValueError:
                pass
        return None

    def _parse_int(self, value: Any) -> Optional[int]:
        """Parse integer value."""
        if value is None:
            return None

        if isinstance(value, int):
            return value

        if isinstance(value, float):
            return int(value)

        s = str(value)
        match = re.search(r'(\d+)', s)
        if match:
            try:
                return int(match.group(1))
            except ValueError:
                pass
        return None

    def _format_shutter_speed(self, value: float) -> str:
        """Format shutter speed as a fraction string."""
        if value >= 1:
            return f"{value}s"
        else:
            denominator = int(round(1 / value))
            return f"1/{denominator}"

    def _parse_gps_coordinate(self, value: Any, ref: Optional[str] = None) -> Optional[float]:
        """Parse GPS coordinate value."""
        if value is None:
            return None

        coord = None

        if isinstance(value, (int, float)):
            coord = float(value)
        else:
            s = str(value)
            # Try to parse as decimal
            try:
                coord = float(s)
            except ValueError:
                # Try to parse DMS format (degrees minutes seconds)
                match = re.match(r"(\d+)\s*[°d]\s*(\d+)\s*['m]\s*([\d.]+)\s*[\"s]?", s)
                if match:
                    d, m, sec = map(float, match.groups())
                    coord = d + m / 60 + sec / 3600

        if coord is not None and ref:
            if ref.upper() in ('S', 'W'):
                coord = -abs(coord)

        return coord
