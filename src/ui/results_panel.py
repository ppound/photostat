"""Results panel with grid/list view and facets."""

import os
import subprocess
import sys
from typing import List, Dict, Any, Optional
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QSplitter,
    QTableWidget, QTableWidgetItem, QHeaderView,
    QPushButton, QLabel, QGroupBox, QScrollArea,
    QFrame, QComboBox, QSpinBox, QTextEdit,
    QAbstractItemView, QListWidget, QListWidgetItem,
    QSizePolicy
)
from PyQt6.QtCore import Qt, pyqtSignal, QSize
from PyQt6.QtGui import QColor, QPixmap, QImage, QCursor

from ..models.image_metadata import ImageMetadata


class ThumbnailLoader:
    """Helper class to load thumbnails from various image formats."""

    # RAW file extensions that need special handling
    RAW_EXTENSIONS = {
        '.raw', '.cr2', '.cr3', '.nef', '.arw', '.orf', '.rw2',
        '.dng', '.pef', '.srw', '.raf', '.3fr', '.ari', '.bay',
        '.crw', '.dcr', '.erf', '.fff', '.mef', '.mrw', '.nrw',
        '.ptx', '.pxn', '.r3d', '.rwl', '.rwz', '.sr2', '.srf', '.x3f'
    }

    _exiftool_path = None

    @classmethod
    def get_exiftool_path(cls):
        """Get exiftool path (cached)."""
        if cls._exiftool_path is None:
            import shutil
            # Search common locations
            search_paths = [
                os.path.expanduser("~/.local/bin/exiftool"),
                "/usr/bin/exiftool",
                "/usr/local/bin/exiftool",
                "/tmp/exiftool-12.70/exiftool",
            ]
            cls._exiftool_path = shutil.which("exiftool")
            if not cls._exiftool_path:
                for path in search_paths:
                    if os.path.isfile(path) and os.access(path, os.X_OK):
                        cls._exiftool_path = path
                        break
        return cls._exiftool_path

    @classmethod
    def is_raw_file(cls, file_path: str) -> bool:
        """Check if file is a RAW format."""
        ext = os.path.splitext(file_path)[1].lower()
        return ext in cls.RAW_EXTENSIONS

    @classmethod
    def extract_raw_thumbnail(cls, file_path: str) -> Optional[bytes]:
        """Extract embedded JPEG thumbnail from RAW file using exiftool."""
        exiftool = cls.get_exiftool_path()
        if not exiftool:
            return None

        try:
            # Extract the largest preview/thumbnail image
            result = subprocess.run(
                [exiftool, '-b', '-PreviewImage', file_path],
                capture_output=True,
                timeout=10
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout

            # Try JpgFromRaw if PreviewImage not available
            result = subprocess.run(
                [exiftool, '-b', '-JpgFromRaw', file_path],
                capture_output=True,
                timeout=10
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout

            # Try ThumbnailImage as last resort
            result = subprocess.run(
                [exiftool, '-b', '-ThumbnailImage', file_path],
                capture_output=True,
                timeout=10
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout

        except Exception as e:
            print(f"Error extracting RAW thumbnail: {e}")

        return None

    @classmethod
    def load_pixmap(cls, file_path: str) -> Optional[QPixmap]:
        """Load a pixmap from file, handling RAW files specially."""
        if not file_path or not os.path.exists(file_path):
            return None

        # Try direct loading first (works for JPEG, PNG, etc.)
        pixmap = QPixmap(file_path)
        if not pixmap.isNull():
            return pixmap

        # For RAW files, try to extract embedded thumbnail
        if cls.is_raw_file(file_path):
            thumbnail_data = cls.extract_raw_thumbnail(file_path)
            if thumbnail_data:
                pixmap = QPixmap()
                if pixmap.loadFromData(thumbnail_data):
                    return pixmap

        return None


class ClickableImageLabel(QLabel):
    """A QLabel that displays an image and emits a signal when clicked."""

    clicked = pyqtSignal(str)  # Emits file path when clicked

    def __init__(self, parent=None):
        super().__init__(parent)
        self.file_path = ""
        self.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setStyleSheet("""
            QLabel {
                border: 1px solid #d1d1d1;
                border-radius: 4px;
                background-color: #f5f5f5;
                padding: 5px;
            }
            QLabel:hover {
                border-color: #0078d4;
                background-color: #e8f4fd;
            }
        """)

    def set_image(self, file_path: str, max_size: int = 200):
        """Load and display an image thumbnail."""
        self.file_path = file_path

        if not file_path or not os.path.exists(file_path):
            self.setText("No preview\navailable")
            self.setToolTip("")
            return

        try:
            # Use ThumbnailLoader to handle various formats including RAW
            pixmap = ThumbnailLoader.load_pixmap(file_path)

            if pixmap is None or pixmap.isNull():
                # Show file type info for unsupported formats
                ext = os.path.splitext(file_path)[1].upper()
                self.setText(f"{ext} file\n(preview not available)")
                self.setToolTip(f"Click to open: {file_path}")
                return

            # Scale to fit while maintaining aspect ratio
            scaled = pixmap.scaled(
                max_size, max_size,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation
            )
            self.setPixmap(scaled)
            self.setToolTip(f"Click to open: {file_path}")

        except Exception as e:
            self.setText(f"Error loading\npreview")
            self.setToolTip(str(e))

    def mousePressEvent(self, event):
        """Handle mouse click."""
        if event.button() == Qt.MouseButton.LeftButton and self.file_path:
            self.clicked.emit(self.file_path)
        super().mousePressEvent(event)

    def clear_image(self):
        """Clear the displayed image."""
        self.file_path = ""
        self.clear()
        self.setText("Select an image\nto preview")
        self.setToolTip("")


class FacetWidget(QWidget):
    """Widget displaying a single facet with clickable items."""

    facet_clicked = pyqtSignal(str, str)  # field_name, value

    def __init__(self, title: str, field_name: str, parent=None):
        """Initialize the facet widget."""
        super().__init__(parent)
        self.field_name = field_name
        self._setup_ui(title)

    def _setup_ui(self, title: str):
        """Set up the facet UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 10)
        layout.setSpacing(2)

        # Title
        title_label = QLabel(title)
        title_label.setStyleSheet("font-weight: bold;")
        layout.addWidget(title_label)

        # Items list
        self.items_list = QListWidget()
        self.items_list.setMaximumHeight(150)
        self.items_list.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.items_list.itemClicked.connect(self._on_item_clicked)
        layout.addWidget(self.items_list)

    def _on_item_clicked(self, item: QListWidgetItem):
        """Handle facet item click."""
        value = item.data(Qt.ItemDataRole.UserRole)
        if value:
            self.facet_clicked.emit(self.field_name, value)

    def set_buckets(self, buckets: List[Dict[str, Any]]):
        """Set the facet buckets."""
        self.items_list.clear()

        for bucket in buckets[:20]:  # Limit to top 20
            key = bucket.get('key', '')
            count = bucket.get('doc_count', 0)
            if key:
                item = QListWidgetItem(f"{key} ({count})")
                item.setData(Qt.ItemDataRole.UserRole, key)
                self.items_list.addItem(item)


class FacetsSidebar(QWidget):
    """Sidebar containing all facets."""

    facet_clicked = pyqtSignal(str, str)  # field_name, value

    def __init__(self, parent=None):
        """Initialize the facets sidebar."""
        super().__init__(parent)
        self.setMinimumWidth(150)
        self._setup_ui()

    def _setup_ui(self):
        """Set up the sidebar UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)

        # Title
        title = QLabel("Facets")
        title.setStyleSheet("font-size: 14px; font-weight: bold;")
        layout.addWidget(title)

        # Scroll area for facets
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)

        facets_widget = QWidget()
        self.facets_layout = QVBoxLayout(facets_widget)
        self.facets_layout.setContentsMargins(0, 0, 0, 0)

        # Create facet widgets
        self.camera_make_facet = FacetWidget("Camera Make", "camera_make")
        self.camera_make_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.camera_make_facet)

        self.camera_model_facet = FacetWidget("Camera Model", "camera_model")
        self.camera_model_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.camera_model_facet)

        self.lens_facet = FacetWidget("Lens", "lens_model")
        self.lens_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.lens_facet)

        self.file_type_facet = FacetWidget("File Type", "file_type")
        self.file_type_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.file_type_facet)

        self.iso_facet = FacetWidget("ISO Range", "iso_range")
        self.iso_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.iso_facet)

        self.date_facet = FacetWidget("Year/Month", "year_month")
        self.date_facet.facet_clicked.connect(self.facet_clicked)
        self.facets_layout.addWidget(self.date_facet)

        self.facets_layout.addStretch()

        scroll.setWidget(facets_widget)
        layout.addWidget(scroll)

    def update_facets(self, aggregations: Dict[str, Any]):
        """Update all facets with aggregation data."""
        self.camera_make_facet.set_buckets(
            aggregations.get('camera_make', {}).get('buckets', [])
        )
        self.camera_model_facet.set_buckets(
            aggregations.get('camera_model', {}).get('buckets', [])
        )
        self.lens_facet.set_buckets(
            aggregations.get('lens_model', {}).get('buckets', [])
        )
        self.file_type_facet.set_buckets(
            aggregations.get('file_type', {}).get('buckets', [])
        )
        self.iso_facet.set_buckets(
            aggregations.get('iso_ranges', {}).get('buckets', [])
        )
        self.date_facet.set_buckets(
            aggregations.get('year_month', {}).get('buckets', [])
        )


class DetailPanel(QWidget):
    """Panel showing detailed EXIF information for selected image."""

    def __init__(self, parent=None):
        """Initialize the detail panel."""
        super().__init__(parent)
        self.setMinimumWidth(200)
        self.current_metadata: Optional[ImageMetadata] = None
        self._setup_ui()

    def _setup_ui(self):
        """Set up the detail panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)

        # Title
        title = QLabel("Image Details")
        title.setStyleSheet("font-size: 14px; font-weight: bold;")
        layout.addWidget(title)

        # Thumbnail preview
        self.thumbnail = ClickableImageLabel()
        self.thumbnail.setMinimumHeight(150)
        self.thumbnail.setMaximumHeight(220)
        self.thumbnail.clicked.connect(self._open_image)
        self.thumbnail.clear_image()
        layout.addWidget(self.thumbnail)

        # Open button
        btn_layout = QHBoxLayout()
        self.open_btn = QPushButton("Open in Viewer")
        self.open_btn.clicked.connect(self._open_current_image)
        self.open_btn.setEnabled(False)
        btn_layout.addWidget(self.open_btn)

        self.open_folder_btn = QPushButton("Open Folder")
        self.open_folder_btn.clicked.connect(self._open_folder)
        self.open_folder_btn.setEnabled(False)
        btn_layout.addWidget(self.open_folder_btn)

        layout.addLayout(btn_layout)

        # Details text
        self.details_text = QTextEdit()
        self.details_text.setReadOnly(True)
        layout.addWidget(self.details_text)

    def _open_image(self, file_path: str):
        """Open image in system default viewer."""
        if not file_path or not os.path.exists(file_path):
            return

        try:
            if sys.platform == 'win32':
                os.startfile(file_path)
            elif sys.platform == 'darwin':
                subprocess.run(['open', file_path], check=False)
            else:
                # Check if running in WSL
                is_wsl = os.path.exists('/mnt/c/Windows')

                if is_wsl:
                    # Use Windows tools to open files
                    # Convert WSL path to Windows path if needed
                    if file_path.startswith('/mnt/'):
                        # Convert /mnt/c/... to C:\...
                        parts = file_path.split('/')
                        drive = parts[2].upper()
                        win_path = f"{drive}:\\" + "\\".join(parts[3:])
                    else:
                        # Use wslpath to convert
                        try:
                            result = subprocess.run(
                                ['wslpath', '-w', file_path],
                                capture_output=True, text=True
                            )
                            win_path = result.stdout.strip()
                        except:
                            win_path = file_path

                    # Use explorer.exe or cmd.exe to open
                    try:
                        subprocess.Popen(
                            ['explorer.exe', win_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL
                        )
                        return
                    except:
                        pass

                    try:
                        subprocess.Popen(
                            ['cmd.exe', '/c', 'start', '', win_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL
                        )
                        return
                    except:
                        pass

                # Linux - try various viewers
                viewers = ['xdg-open', 'eog', 'feh', 'gpicview', 'display', 'xv']
                for viewer in viewers:
                    try:
                        subprocess.Popen([viewer, file_path],
                                       stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
                        return
                    except FileNotFoundError:
                        continue

        except Exception as e:
            print(f"Error opening image: {e}")

    def _open_current_image(self):
        """Open the currently displayed image."""
        if self.current_metadata:
            self._open_image(self.current_metadata.file_path)

    def _open_folder(self):
        """Open the folder containing the current image."""
        if not self.current_metadata:
            return

        folder = os.path.dirname(self.current_metadata.file_path)
        if not os.path.exists(folder):
            return

        try:
            if sys.platform == 'win32':
                os.startfile(folder)
            elif sys.platform == 'darwin':
                subprocess.run(['open', folder], check=False)
            else:
                # Check if running in WSL
                is_wsl = os.path.exists('/mnt/c/Windows')

                if is_wsl:
                    # Convert WSL path to Windows path
                    if folder.startswith('/mnt/'):
                        parts = folder.split('/')
                        drive = parts[2].upper()
                        win_path = f"{drive}:\\" + "\\".join(parts[3:])
                    else:
                        try:
                            result = subprocess.run(
                                ['wslpath', '-w', folder],
                                capture_output=True, text=True
                            )
                            win_path = result.stdout.strip()
                        except:
                            win_path = folder

                    try:
                        subprocess.Popen(
                            ['explorer.exe', win_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL
                        )
                        return
                    except:
                        pass

                # Linux
                viewers = ['xdg-open', 'nautilus', 'dolphin', 'thunar', 'pcmanfm']
                for viewer in viewers:
                    try:
                        subprocess.Popen([viewer, folder],
                                       stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
                        return
                    except FileNotFoundError:
                        continue

        except Exception as e:
            print(f"Error opening folder: {e}")

    def show_metadata(self, metadata: Optional[ImageMetadata]):
        """Display metadata for an image."""
        self.current_metadata = metadata

        if not metadata:
            self.details_text.clear()
            self.thumbnail.clear_image()
            self.open_btn.setEnabled(False)
            self.open_folder_btn.setEnabled(False)
            return

        # Update thumbnail
        self.thumbnail.set_image(metadata.file_path)
        self.open_btn.setEnabled(os.path.exists(metadata.file_path))
        self.open_folder_btn.setEnabled(os.path.exists(os.path.dirname(metadata.file_path)))

        lines = []

        # File info
        lines.append("=== File Information ===")
        lines.append(f"Name: {metadata.file_name}")
        lines.append(f"Path: {metadata.file_path}")
        lines.append(f"Size: {self._format_size(metadata.file_size)}")
        lines.append(f"Type: {metadata.file_type.upper()}")

        # Date
        lines.append("")
        lines.append("=== Date ===")
        if metadata.date_taken:
            lines.append(f"Taken: {metadata.date_taken.strftime('%Y-%m-%d %H:%M:%S')}")
        if metadata.date_indexed:
            lines.append(f"Indexed: {metadata.date_indexed.strftime('%Y-%m-%d %H:%M:%S')}")

        # Camera
        lines.append("")
        lines.append("=== Camera ===")
        if metadata.camera_make:
            lines.append(f"Make: {metadata.camera_make}")
        if metadata.camera_model:
            lines.append(f"Model: {metadata.camera_model}")
        if metadata.lens_model:
            lines.append(f"Lens: {metadata.lens_model}")

        # Exposure
        lines.append("")
        lines.append("=== Exposure ===")
        if metadata.focal_length is not None:
            lines.append(f"Focal Length: {metadata.focal_length}mm")
        if metadata.aperture is not None:
            lines.append(f"Aperture: f/{metadata.aperture}")
        if metadata.shutter_speed:
            lines.append(f"Shutter Speed: {metadata.shutter_speed}")
        if metadata.iso is not None:
            lines.append(f"ISO: {metadata.iso}")
        if metadata.exposure_mode:
            lines.append(f"Mode: {metadata.exposure_mode}")
        if metadata.white_balance:
            lines.append(f"White Balance: {metadata.white_balance}")
        if metadata.flash:
            lines.append(f"Flash: {metadata.flash}")

        # Dimensions
        lines.append("")
        lines.append("=== Dimensions ===")
        if metadata.image_width and metadata.image_height:
            lines.append(f"Size: {metadata.image_width} x {metadata.image_height}")
        if metadata.orientation:
            lines.append(f"Orientation: {metadata.orientation}")

        # GPS
        if metadata.gps_latitude is not None and metadata.gps_longitude is not None:
            lines.append("")
            lines.append("=== GPS ===")
            lines.append(f"Latitude: {metadata.gps_latitude:.6f}")
            lines.append(f"Longitude: {metadata.gps_longitude:.6f}")

        # Other
        lines.append("")
        lines.append("=== Other ===")
        if metadata.artist:
            lines.append(f"Artist: {metadata.artist}")
        if metadata.copyright:
            lines.append(f"Copyright: {metadata.copyright}")
        if metadata.software:
            lines.append(f"Software: {metadata.software}")

        self.details_text.setText("\n".join(lines))

    def _format_size(self, size: int) -> str:
        """Format file size in human-readable format."""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024:
                return f"{size:.1f} {unit}"
            size /= 1024
        return f"{size:.1f} TB"


class ResultsPanel(QWidget):
    """Panel for displaying search results with facets."""

    facet_filter_requested = pyqtSignal(str, str)  # field_name, value
    page_changed = pyqtSignal(int)  # page number

    def __init__(self, parent=None):
        """Initialize the results panel."""
        super().__init__(parent)
        self.results: List[ImageMetadata] = []
        self.current_page = 0
        self.page_size = 50
        self.total_results = 0
        self._setup_ui()

    def _setup_ui(self):
        """Set up the panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        # Main splitter
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # Facets sidebar
        self.facets_sidebar = FacetsSidebar()
        self.facets_sidebar.facet_clicked.connect(self._on_facet_clicked)
        splitter.addWidget(self.facets_sidebar)

        # Center content
        center_widget = QWidget()
        center_layout = QVBoxLayout(center_widget)
        center_layout.setContentsMargins(10, 10, 10, 10)

        # Toolbar
        toolbar = QHBoxLayout()

        self.results_label = QLabel("No results")
        toolbar.addWidget(self.results_label)

        toolbar.addStretch()

        toolbar.addWidget(QLabel("Sort by:"))
        self.sort_combo = QComboBox()
        self.sort_combo.addItem("Date Taken (Newest)", "date_taken:desc")
        self.sort_combo.addItem("Date Taken (Oldest)", "date_taken:asc")
        self.sort_combo.addItem("File Name (A-Z)", "file_name.keyword:asc")
        self.sort_combo.addItem("File Name (Z-A)", "file_name.keyword:desc")
        self.sort_combo.addItem("Camera", "camera_model:asc")
        self.sort_combo.addItem("ISO", "iso:asc")
        self.sort_combo.currentIndexChanged.connect(self._on_sort_changed)
        toolbar.addWidget(self.sort_combo)

        center_layout.addLayout(toolbar)

        # Results table
        self.results_table = QTableWidget()
        self.results_table.setColumnCount(8)
        self.results_table.setHorizontalHeaderLabels([
            "File Name", "Date Taken", "Camera", "Lens",
            "Focal Length", "Aperture", "ISO", "File Path"
        ])
        self.results_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.results_table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.results_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.results_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.results_table.horizontalHeader().setStretchLastSection(True)
        self.results_table.itemSelectionChanged.connect(self._on_selection_changed)
        center_layout.addWidget(self.results_table)

        # Pagination
        pagination = QHBoxLayout()

        self.prev_btn = QPushButton("Previous")
        self.prev_btn.clicked.connect(self._prev_page)
        self.prev_btn.setEnabled(False)
        pagination.addWidget(self.prev_btn)

        pagination.addStretch()

        self.page_label = QLabel("Page 1")
        pagination.addWidget(self.page_label)

        pagination.addStretch()

        self.next_btn = QPushButton("Next")
        self.next_btn.clicked.connect(self._next_page)
        self.next_btn.setEnabled(False)
        pagination.addWidget(self.next_btn)

        center_layout.addLayout(pagination)

        splitter.addWidget(center_widget)

        # Detail panel
        self.detail_panel = DetailPanel()
        splitter.addWidget(self.detail_panel)

        # Set splitter proportions and stretch factors
        splitter.setSizes([200, 600, 300])
        splitter.setStretchFactor(0, 0)  # Facets sidebar doesn't stretch by default
        splitter.setStretchFactor(1, 1)  # Results table stretches most
        splitter.setStretchFactor(2, 0)  # Detail panel doesn't stretch by default

        # Make splitter handles more visible
        splitter.setHandleWidth(5)
        splitter.setStyleSheet("""
            QSplitter::handle {
                background-color: #d1d1d1;
            }
            QSplitter::handle:hover {
                background-color: #0078d4;
            }
        """)

        layout.addWidget(splitter)

    def set_results(
        self,
        results: List[ImageMetadata],
        total: int,
        aggregations: Dict[str, Any],
        page: int = 0
    ):
        """Set the search results."""
        self.results = results
        self.total_results = total
        self.current_page = page

        # Update results label
        start = page * self.page_size + 1
        end = min(start + len(results) - 1, total)
        if total > 0:
            self.results_label.setText(f"Showing {start}-{end} of {total} results")
        else:
            self.results_label.setText("No results")

        # Update table
        self.results_table.setRowCount(len(results))
        for i, metadata in enumerate(results):
            self._set_table_row(i, metadata)

        # Update facets
        self.facets_sidebar.update_facets(aggregations)

        # Update pagination
        self._update_pagination()

        # Clear detail panel
        self.detail_panel.show_metadata(None)

    def _set_table_row(self, row: int, metadata: ImageMetadata):
        """Set a single table row."""
        # File name
        self.results_table.setItem(row, 0, QTableWidgetItem(metadata.file_name))

        # Date taken
        date_str = ""
        if metadata.date_taken:
            date_str = metadata.date_taken.strftime("%Y-%m-%d %H:%M")
        self.results_table.setItem(row, 1, QTableWidgetItem(date_str))

        # Camera
        camera = ""
        if metadata.camera_make and metadata.camera_model:
            camera = f"{metadata.camera_make} {metadata.camera_model}"
        elif metadata.camera_model:
            camera = metadata.camera_model
        self.results_table.setItem(row, 2, QTableWidgetItem(camera))

        # Lens
        self.results_table.setItem(row, 3, QTableWidgetItem(metadata.lens_model or ""))

        # Focal length
        focal = ""
        if metadata.focal_length is not None:
            focal = f"{metadata.focal_length}mm"
        self.results_table.setItem(row, 4, QTableWidgetItem(focal))

        # Aperture
        aperture = ""
        if metadata.aperture is not None:
            aperture = f"f/{metadata.aperture}"
        self.results_table.setItem(row, 5, QTableWidgetItem(aperture))

        # ISO
        iso = ""
        if metadata.iso is not None:
            iso = str(metadata.iso)
        self.results_table.setItem(row, 6, QTableWidgetItem(iso))

        # File path
        self.results_table.setItem(row, 7, QTableWidgetItem(metadata.file_path))

    def _update_pagination(self):
        """Update pagination controls."""
        total_pages = max(1, (self.total_results + self.page_size - 1) // self.page_size)

        self.page_label.setText(f"Page {self.current_page + 1} of {total_pages}")

        self.prev_btn.setEnabled(self.current_page > 0)
        self.next_btn.setEnabled(self.current_page < total_pages - 1)

    def _prev_page(self):
        """Go to previous page."""
        if self.current_page > 0:
            self.current_page -= 1
            self.page_changed.emit(self.current_page)

    def _next_page(self):
        """Go to next page."""
        total_pages = (self.total_results + self.page_size - 1) // self.page_size
        if self.current_page < total_pages - 1:
            self.current_page += 1
            self.page_changed.emit(self.current_page)

    def _on_selection_changed(self):
        """Handle row selection change."""
        rows = self.results_table.selectionModel().selectedRows()
        if rows:
            row = rows[0].row()
            if 0 <= row < len(self.results):
                self.detail_panel.show_metadata(self.results[row])
        else:
            self.detail_panel.show_metadata(None)

    def _on_facet_clicked(self, field: str, value: str):
        """Handle facet click."""
        self.facet_filter_requested.emit(field, value)

    def _on_sort_changed(self):
        """Handle sort change - emit page change to refresh."""
        self.page_changed.emit(0)

    def get_sort(self) -> tuple:
        """Get current sort field and order."""
        data = self.sort_combo.currentData()
        if data:
            parts = data.split(':')
            return parts[0], parts[1]
        return "date_taken", "desc"

    def clear(self):
        """Clear all results."""
        self.results = []
        self.total_results = 0
        self.current_page = 0
        self.results_table.setRowCount(0)
        self.results_label.setText("No results")
        self.detail_panel.show_metadata(None)
        self._update_pagination()
