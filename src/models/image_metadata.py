"""Data models for EXIF metadata."""

from dataclasses import dataclass, field, asdict
from datetime import datetime
from typing import Optional, Dict, Any


@dataclass
class ImageMetadata:
    """Represents extracted EXIF metadata from an image file."""

    # File information
    file_path: str
    file_name: str
    file_size: int = 0
    file_type: str = ""

    # Timestamps
    date_indexed: Optional[datetime] = None
    date_taken: Optional[datetime] = None

    # Camera information
    camera_make: Optional[str] = None
    camera_model: Optional[str] = None
    lens_model: Optional[str] = None

    # Exposure settings
    focal_length: Optional[float] = None
    aperture: Optional[float] = None
    shutter_speed: Optional[str] = None
    iso: Optional[int] = None
    exposure_mode: Optional[str] = None
    white_balance: Optional[str] = None
    flash: Optional[str] = None

    # Image dimensions
    image_width: Optional[int] = None
    image_height: Optional[int] = None
    orientation: Optional[str] = None

    # GPS data
    gps_latitude: Optional[float] = None
    gps_longitude: Optional[float] = None

    # Other metadata
    artist: Optional[str] = None
    copyright: Optional[str] = None
    software: Optional[str] = None

    # Raw EXIF data (stored but not indexed for search)
    all_exif: Dict[str, Any] = field(default_factory=dict)

    def to_opensearch_doc(self) -> Dict[str, Any]:
        """Convert to OpenSearch document format."""
        doc = {}

        # File information
        doc['file_path'] = self.file_path
        doc['file_name'] = self.file_name
        doc['file_size'] = self.file_size
        doc['file_type'] = self.file_type

        # Timestamps - convert to ISO format strings
        if self.date_indexed:
            doc['date_indexed'] = self.date_indexed.isoformat()
        if self.date_taken:
            doc['date_taken'] = self.date_taken.isoformat()

        # Camera information
        if self.camera_make:
            doc['camera_make'] = self.camera_make
        if self.camera_model:
            doc['camera_model'] = self.camera_model
        if self.lens_model:
            doc['lens_model'] = self.lens_model

        # Exposure settings
        if self.focal_length is not None:
            doc['focal_length'] = self.focal_length
        if self.aperture is not None:
            doc['aperture'] = self.aperture
        if self.shutter_speed:
            doc['shutter_speed'] = self.shutter_speed
        if self.iso is not None:
            doc['iso'] = self.iso
        if self.exposure_mode:
            doc['exposure_mode'] = self.exposure_mode
        if self.white_balance:
            doc['white_balance'] = self.white_balance
        if self.flash:
            doc['flash'] = self.flash

        # Image dimensions
        if self.image_width is not None:
            doc['image_width'] = self.image_width
        if self.image_height is not None:
            doc['image_height'] = self.image_height
        if self.orientation:
            doc['orientation'] = self.orientation

        # GPS data
        if self.gps_latitude is not None and self.gps_longitude is not None:
            doc['gps_latitude'] = self.gps_latitude
            doc['gps_longitude'] = self.gps_longitude
            doc['gps_location'] = {
                'lat': self.gps_latitude,
                'lon': self.gps_longitude
            }

        # Other metadata
        if self.artist:
            doc['artist'] = self.artist
        if self.copyright:
            doc['copyright'] = self.copyright
        if self.software:
            doc['software'] = self.software

        # Raw EXIF data
        doc['all_exif'] = self.all_exif

        return doc

    @classmethod
    def from_opensearch_doc(cls, doc: Dict[str, Any]) -> 'ImageMetadata':
        """Create ImageMetadata from OpenSearch document."""
        metadata = cls(
            file_path=doc.get('file_path', ''),
            file_name=doc.get('file_name', ''),
            file_size=doc.get('file_size', 0),
            file_type=doc.get('file_type', ''),
        )

        # Parse timestamps
        if 'date_indexed' in doc and doc['date_indexed']:
            try:
                metadata.date_indexed = datetime.fromisoformat(doc['date_indexed'].replace('Z', '+00:00'))
            except (ValueError, AttributeError):
                pass

        if 'date_taken' in doc and doc['date_taken']:
            try:
                metadata.date_taken = datetime.fromisoformat(doc['date_taken'].replace('Z', '+00:00'))
            except (ValueError, AttributeError):
                pass

        # Camera information
        metadata.camera_make = doc.get('camera_make')
        metadata.camera_model = doc.get('camera_model')
        metadata.lens_model = doc.get('lens_model')

        # Exposure settings
        metadata.focal_length = doc.get('focal_length')
        metadata.aperture = doc.get('aperture')
        metadata.shutter_speed = doc.get('shutter_speed')
        metadata.iso = doc.get('iso')
        metadata.exposure_mode = doc.get('exposure_mode')
        metadata.white_balance = doc.get('white_balance')
        metadata.flash = doc.get('flash')

        # Image dimensions
        metadata.image_width = doc.get('image_width')
        metadata.image_height = doc.get('image_height')
        metadata.orientation = doc.get('orientation')

        # GPS data
        metadata.gps_latitude = doc.get('gps_latitude')
        metadata.gps_longitude = doc.get('gps_longitude')

        # Other metadata
        metadata.artist = doc.get('artist')
        metadata.copyright = doc.get('copyright')
        metadata.software = doc.get('software')

        # Raw EXIF data
        metadata.all_exif = doc.get('all_exif', {})

        return metadata
