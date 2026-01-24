"""Index panel for directory management and indexing."""

import os
import subprocess
from typing import List, Optional
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QListWidget, QListWidgetItem,
    QPushButton, QProgressBar, QLabel, QFileDialog, QGroupBox,
    QCheckBox, QMessageBox, QFrame, QDialog, QLineEdit, QComboBox,
    QTreeView, QHeaderView, QDialogButtonBox, QGridLayout, QInputDialog
)
from PyQt6.QtCore import Qt, pyqtSignal, QDir
from PyQt6.QtGui import QFileSystemModel, QIcon

from ..services.opensearch_service import OpenSearchService
from ..services.indexer_worker import IndexerWorker, DirectoryScanWorker
from ..services.exif_service import ExifService


class DriveBrowserDialog(QDialog):
    """Custom dialog for browsing Windows drives in WSL."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.selected_path = ""
        self._setup_ui()
        self._detect_drives()

    def _setup_ui(self):
        """Set up the dialog UI."""
        self.setWindowTitle("Select Directory")
        self.setMinimumSize(700, 500)

        layout = QVBoxLayout(self)

        # Drive selection
        drive_layout = QHBoxLayout()
        drive_layout.addWidget(QLabel("Location:"))

        self.drive_combo = QComboBox()
        self.drive_combo.setMinimumWidth(200)
        self.drive_combo.currentIndexChanged.connect(self._on_drive_changed)
        drive_layout.addWidget(self.drive_combo)

        drive_layout.addStretch()

        # Manual path entry
        self.path_edit = QLineEdit()
        self.path_edit.setPlaceholderText("Or enter path manually...")
        self.path_edit.returnPressed.connect(self._on_path_entered)
        drive_layout.addWidget(self.path_edit, 1)

        self.go_btn = QPushButton("Go")
        self.go_btn.clicked.connect(self._on_path_entered)
        drive_layout.addWidget(self.go_btn)

        layout.addLayout(drive_layout)

        # File system tree
        self.fs_model = QFileSystemModel()
        self.fs_model.setFilter(QDir.Filter.AllDirs | QDir.Filter.NoDotAndDotDot)

        self.tree_view = QTreeView()
        self.tree_view.setModel(self.fs_model)
        self.tree_view.setHeaderHidden(False)
        self.tree_view.header().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        self.tree_view.hideColumn(1)  # Size
        self.tree_view.hideColumn(2)  # Type
        self.tree_view.setColumnWidth(3, 150)  # Date modified
        self.tree_view.doubleClicked.connect(self._on_item_double_clicked)
        self.tree_view.clicked.connect(self._on_item_clicked)

        layout.addWidget(self.tree_view)

        # Selected path display
        selected_layout = QHBoxLayout()
        selected_layout.addWidget(QLabel("Selected:"))
        self.selected_label = QLabel("")
        self.selected_label.setStyleSheet("font-weight: bold;")
        selected_layout.addWidget(self.selected_label, 1)
        layout.addLayout(selected_layout)

        # Buttons
        button_box = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        button_box.accepted.connect(self.accept)
        button_box.rejected.connect(self.reject)
        layout.addWidget(button_box)

    def _detect_drives(self):
        """Detect available Windows drives and common locations."""
        self.drive_combo.clear()

        # Add common locations
        locations = []

        # Home directory
        home = os.path.expanduser("~")
        if os.path.exists(home):
            locations.append(("Home", home))

        # WSL mounted Windows drives
        mnt_path = "/mnt"
        if os.path.exists(mnt_path):
            try:
                for item in sorted(os.listdir(mnt_path)):
                    drive_path = os.path.join(mnt_path, item)
                    if os.path.isdir(drive_path) and len(item) == 1 and item.isalpha():
                        # This looks like a Windows drive (c, d, e, etc.)
                        locations.append((f"Drive {item.upper()}:", drive_path))
            except PermissionError:
                pass

        # Check for common Windows user folders
        username = os.environ.get('USER', '')
        windows_user_paths = [
            f"/mnt/c/Users/{username}",
            f"/mnt/c/Users/{username}/Pictures",
            f"/mnt/c/Users/{username}/Documents",
            f"/mnt/c/Users/{username}/Downloads",
        ]
        for path in windows_user_paths:
            if os.path.exists(path):
                name = os.path.basename(path)
                if name == username:
                    name = f"Windows User ({username})"
                locations.append((name, path))

        # External drives often mount as /mnt/d, /mnt/e, etc. or under /media
        media_path = "/media"
        if os.path.exists(media_path):
            try:
                for user_dir in os.listdir(media_path):
                    user_media = os.path.join(media_path, user_dir)
                    if os.path.isdir(user_media):
                        for drive in os.listdir(user_media):
                            drive_path = os.path.join(user_media, drive)
                            if os.path.isdir(drive_path):
                                locations.append((f"External: {drive}", drive_path))
            except PermissionError:
                pass

        # Root
        locations.append(("Root (/)", "/"))

        # Add to combo
        for name, path in locations:
            self.drive_combo.addItem(name, path)

        # Set default to first Windows drive if available, otherwise home
        for i in range(self.drive_combo.count()):
            if self.drive_combo.itemData(i, Qt.ItemDataRole.UserRole).startswith("/mnt/"):
                self.drive_combo.setCurrentIndex(i)
                break

    def _on_drive_changed(self):
        """Handle drive selection change."""
        path = self.drive_combo.currentData()
        if path and os.path.exists(path):
            self._navigate_to(path)

    def _on_path_entered(self):
        """Handle manual path entry."""
        path = self.path_edit.text().strip()

        # Convert Windows path to WSL path if needed
        if len(path) >= 2 and path[1] == ':':
            # Windows path like C:\Users\...
            drive = path[0].lower()
            rest = path[2:].replace('\\', '/')
            path = f"/mnt/{drive}{rest}"

        if os.path.exists(path):
            self._navigate_to(path)
        else:
            QMessageBox.warning(self, "Invalid Path", f"Path does not exist:\n{path}")

    def _navigate_to(self, path: str):
        """Navigate the tree view to a path."""
        self.fs_model.setRootPath(path)
        index = self.fs_model.index(path)
        self.tree_view.setRootIndex(index)
        self.path_edit.setText(path)
        self.selected_path = path
        self.selected_label.setText(path)

    def _on_item_clicked(self, index):
        """Handle item click."""
        path = self.fs_model.filePath(index)
        self.selected_path = path
        self.selected_label.setText(path)

    def _on_item_double_clicked(self, index):
        """Handle item double-click to navigate into folder."""
        path = self.fs_model.filePath(index)
        if os.path.isdir(path):
            self._navigate_to(path)

    def get_selected_path(self) -> str:
        """Get the selected directory path."""
        return self.selected_path


class IndexPanel(QWidget):
    """Panel for managing directories and indexing images."""

    indexing_started = pyqtSignal()
    indexing_finished = pyqtSignal()

    def __init__(self, opensearch_service: OpenSearchService, parent=None):
        """
        Initialize the index panel.

        Args:
            opensearch_service: OpenSearch service instance
            parent: Parent widget
        """
        super().__init__(parent)
        self.opensearch_service = opensearch_service
        self.directories: List[str] = []
        self._indexer_worker: Optional[IndexerWorker] = None
        self._scan_worker: Optional[DirectoryScanWorker] = None

        self._setup_ui()
        self._update_buttons_state()

    def _setup_ui(self):
        """Set up the panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(15)

        # Header
        header = QLabel("Manage Indexed Directories")
        header.setStyleSheet("font-size: 18px; font-weight: bold;")
        layout.addWidget(header)

        # Directory list group
        dir_group = QGroupBox("Directories to Index")
        dir_layout = QVBoxLayout(dir_group)

        # Directory list
        self.dir_list = QListWidget()
        self.dir_list.setSelectionMode(QListWidget.SelectionMode.SingleSelection)
        self.dir_list.itemSelectionChanged.connect(self._update_buttons_state)
        dir_layout.addWidget(self.dir_list)

        # Directory buttons row 1
        dir_btn_layout = QHBoxLayout()

        self.add_btn = QPushButton("Browse...")
        self.add_btn.clicked.connect(self._add_directory)
        dir_btn_layout.addWidget(self.add_btn)

        self.add_path_btn = QPushButton("Add Path...")
        self.add_path_btn.clicked.connect(self._add_directory_manual)
        dir_btn_layout.addWidget(self.add_path_btn)

        self.remove_btn = QPushButton("Remove Selected")
        self.remove_btn.clicked.connect(self._remove_directory)
        dir_btn_layout.addWidget(self.remove_btn)

        dir_btn_layout.addStretch()

        self.scan_btn = QPushButton("Scan Selected")
        self.scan_btn.clicked.connect(self._scan_directory)
        dir_btn_layout.addWidget(self.scan_btn)

        dir_layout.addLayout(dir_btn_layout)

        # Quick access buttons for Windows drives
        quick_access_layout = QHBoxLayout()
        quick_access_layout.addWidget(QLabel("Quick Access:"))

        self.drive_buttons = []
        self._create_drive_buttons(quick_access_layout)

        quick_access_layout.addStretch()
        dir_layout.addLayout(quick_access_layout)

        layout.addWidget(dir_group)

        # Indexing options
        options_group = QGroupBox("Indexing Options")
        options_layout = QVBoxLayout(options_group)

        self.recursive_check = QCheckBox("Scan directories recursively")
        self.recursive_check.setChecked(True)
        options_layout.addWidget(self.recursive_check)

        layout.addWidget(options_group)

        # Progress section
        progress_group = QGroupBox("Indexing Progress")
        progress_layout = QVBoxLayout(progress_group)

        # Progress bar
        self.progress_bar = QProgressBar()
        self.progress_bar.setMinimum(0)
        self.progress_bar.setMaximum(100)
        self.progress_bar.setValue(0)
        progress_layout.addWidget(self.progress_bar)

        # Progress label
        self.progress_label = QLabel("Ready to index")
        self.progress_label.setWordWrap(True)
        progress_layout.addWidget(self.progress_label)

        # Index buttons
        index_btn_layout = QHBoxLayout()

        self.index_btn = QPushButton("Start Indexing")
        self.index_btn.clicked.connect(self._start_indexing)
        index_btn_layout.addWidget(self.index_btn)

        self.stop_btn = QPushButton("Stop")
        self.stop_btn.clicked.connect(self._stop_indexing)
        self.stop_btn.setEnabled(False)
        index_btn_layout.addWidget(self.stop_btn)

        index_btn_layout.addStretch()

        self.reindex_btn = QPushButton("Re-index All")
        self.reindex_btn.clicked.connect(self._reindex_all)
        index_btn_layout.addWidget(self.reindex_btn)

        progress_layout.addLayout(index_btn_layout)

        layout.addWidget(progress_group)

        # Statistics section
        stats_group = QGroupBox("Index Statistics")
        stats_layout = QVBoxLayout(stats_group)

        self.stats_label = QLabel("No connection")
        stats_layout.addWidget(self.stats_label)

        refresh_btn = QPushButton("Refresh Stats")
        refresh_btn.clicked.connect(self.refresh_stats)
        stats_layout.addWidget(refresh_btn)

        layout.addWidget(stats_group)

        layout.addStretch()

    def _create_drive_buttons(self, layout):
        """Create quick access buttons for detected drives."""
        # Detect Windows drives
        mnt_path = "/mnt"
        if os.path.exists(mnt_path):
            try:
                for item in sorted(os.listdir(mnt_path)):
                    drive_path = os.path.join(mnt_path, item)
                    if os.path.isdir(drive_path) and len(item) == 1 and item.isalpha():
                        btn = QPushButton(f"{item.upper()}:")
                        btn.setMaximumWidth(50)
                        btn.setToolTip(f"Browse {drive_path}")
                        btn.clicked.connect(lambda checked, p=drive_path: self._quick_browse(p))
                        layout.addWidget(btn)
                        self.drive_buttons.append(btn)
            except PermissionError:
                pass

        # Add Pictures folder shortcut if exists
        username = os.environ.get('USER', '')
        pictures_path = f"/mnt/c/Users/{username}/Pictures"
        if os.path.exists(pictures_path):
            btn = QPushButton("Pictures")
            btn.setToolTip(f"Browse {pictures_path}")
            btn.clicked.connect(lambda checked, p=pictures_path: self._quick_browse(p))
            layout.addWidget(btn)
            self.drive_buttons.append(btn)

    def _quick_browse(self, start_path: str):
        """Open browser dialog starting at a specific path."""
        dialog = DriveBrowserDialog(self)
        dialog._navigate_to(start_path)

        if dialog.exec() == QDialog.DialogCode.Accepted:
            directory = dialog.get_selected_path()
            if directory and directory not in self.directories:
                self.directories.append(directory)
                self.dir_list.addItem(directory)
                self._update_buttons_state()

    def _update_buttons_state(self):
        """Update button enabled states based on selection."""
        has_selection = len(self.dir_list.selectedItems()) > 0
        has_directories = self.dir_list.count() > 0
        is_indexing = self._indexer_worker is not None and self._indexer_worker.isRunning()

        self.remove_btn.setEnabled(has_selection and not is_indexing)
        self.scan_btn.setEnabled(has_selection and not is_indexing)
        self.index_btn.setEnabled(has_directories and not is_indexing)
        self.reindex_btn.setEnabled(has_directories and not is_indexing)
        self.stop_btn.setEnabled(is_indexing)
        self.add_btn.setEnabled(not is_indexing)
        self.add_path_btn.setEnabled(not is_indexing)

        for btn in self.drive_buttons:
            btn.setEnabled(not is_indexing)

    def _add_directory(self):
        """Add a directory using the custom drive browser."""
        dialog = DriveBrowserDialog(self)

        if dialog.exec() == QDialog.DialogCode.Accepted:
            directory = dialog.get_selected_path()
            if directory and directory not in self.directories:
                self.directories.append(directory)
                self.dir_list.addItem(directory)
                self._update_buttons_state()

    def _add_directory_manual(self):
        """Add a directory by entering path manually."""
        path, ok = QInputDialog.getText(
            self,
            "Add Directory Path",
            "Enter directory path:\n\n"
            "Examples:\n"
            "  Windows: C:\\Users\\Name\\Pictures\n"
            "  WSL: /mnt/c/Users/Name/Pictures\n"
            "  Linux: /home/user/photos",
            QLineEdit.EchoMode.Normal,
            ""
        )

        if ok and path:
            # Convert Windows path to WSL path if needed
            if len(path) >= 2 and path[1] == ':':
                drive = path[0].lower()
                rest = path[2:].replace('\\', '/')
                path = f"/mnt/{drive}{rest}"

            # Clean up path
            path = path.strip()

            if not os.path.exists(path):
                QMessageBox.warning(
                    self,
                    "Invalid Path",
                    f"Directory does not exist:\n{path}"
                )
                return

            if not os.path.isdir(path):
                QMessageBox.warning(
                    self,
                    "Invalid Path",
                    f"Path is not a directory:\n{path}"
                )
                return

            if path not in self.directories:
                self.directories.append(path)
                self.dir_list.addItem(path)
                self._update_buttons_state()

    def _remove_directory(self):
        """Remove the selected directory."""
        current_item = self.dir_list.currentItem()
        if current_item:
            directory = current_item.text()

            # Confirm removal
            reply = QMessageBox.question(
                self,
                "Remove Directory",
                f"Remove '{directory}' from the list?\n\n"
                "Note: This will not delete indexed images from OpenSearch.",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
            )

            if reply == QMessageBox.StandardButton.Yes:
                self.directories.remove(directory)
                self.dir_list.takeItem(self.dir_list.currentRow())
                self._update_buttons_state()

    def _scan_directory(self):
        """Scan selected directory to count files."""
        current_item = self.dir_list.currentItem()
        if not current_item:
            return

        directory = current_item.text()
        self.progress_label.setText(f"Scanning {directory}...")

        self._scan_worker = DirectoryScanWorker(
            directory,
            recursive=self.recursive_check.isChecked(),
            parent=self
        )
        self._scan_worker.scan_complete.connect(self._on_scan_complete)
        self._scan_worker.error_occurred.connect(self._on_scan_error)
        self._scan_worker.start()

    def _on_scan_complete(self, files: List[str]):
        """Handle scan completion."""
        count = len(files)
        self.progress_label.setText(f"Found {count} image files")
        self._scan_worker = None

    def _on_scan_error(self, error: str):
        """Handle scan error."""
        self.progress_label.setText(f"Scan error: {error}")
        self._scan_worker = None

    def _start_indexing(self):
        """Start the indexing process."""
        if not self.directories:
            QMessageBox.warning(self, "No Directories", "Please add directories to index first.")
            return

        if not self.opensearch_service.is_connected():
            QMessageBox.warning(
                self,
                "Not Connected",
                "Please connect to OpenSearch first (check Settings)."
            )
            return

        # Check if ExifTool is available
        if not ExifService.is_exiftool_available():
            exiftool_path = ExifService.get_exiftool_path()
            QMessageBox.critical(
                self,
                "ExifTool Not Found",
                "ExifTool is required for extracting image metadata.\n\n"
                "Please install ExifTool:\n"
                "  1. Download from https://exiftool.org\n"
                "  2. Extract and place 'exiftool' in ~/.local/bin/\n"
                "  3. Or install via: apt install libimage-exiftool-perl\n\n"
                "After installing, restart the application."
            )
            return

        self.progress_bar.setValue(0)
        self.progress_label.setText("Starting indexing...")

        self._indexer_worker = IndexerWorker(
            directories=self.directories.copy(),
            opensearch_service=self.opensearch_service,
            recursive=self.recursive_check.isChecked(),
            parent=self
        )

        self._indexer_worker.progress_updated.connect(self._on_progress_updated)
        self._indexer_worker.indexing_complete.connect(self._on_indexing_complete)
        self._indexer_worker.error_occurred.connect(self._on_indexing_error)
        self._indexer_worker.finished.connect(self._on_worker_finished)

        self._update_buttons_state()
        self.indexing_started.emit()
        self._indexer_worker.start()

    def _stop_indexing(self):
        """Stop the indexing process."""
        if self._indexer_worker:
            self._indexer_worker.stop()
            self.progress_label.setText("Stopping...")

    def _reindex_all(self):
        """Delete index and re-index all directories."""
        if not self.directories:
            QMessageBox.warning(self, "No Directories", "Please add directories first.")
            return

        reply = QMessageBox.question(
            self,
            "Re-index All",
            "This will delete all indexed data and re-index from scratch.\n\n"
            "Are you sure you want to continue?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )

        if reply == QMessageBox.StandardButton.Yes:
            # Delete and recreate index
            self.opensearch_service.delete_index()
            self._start_indexing()

    def _on_progress_updated(self, current: int, total: int, current_file: str):
        """Handle progress update from worker."""
        if total > 0:
            percentage = int((current / total) * 100)
            self.progress_bar.setValue(percentage)

            filename = os.path.basename(current_file) if current_file else ""
            self.progress_label.setText(f"Indexing {current}/{total}: {filename}")

    def _on_indexing_complete(self, success_count: int, error_count: int):
        """Handle indexing completion."""
        self.progress_bar.setValue(100)
        self.progress_label.setText(
            f"Indexing complete: {success_count} indexed, {error_count} errors"
        )
        self.refresh_stats()

    def _on_indexing_error(self, error: str):
        """Handle indexing error."""
        QMessageBox.critical(self, "Indexing Error", f"An error occurred: {error}")

    def _on_worker_finished(self):
        """Handle worker thread finished."""
        self._indexer_worker = None
        self._update_buttons_state()
        self.indexing_finished.emit()

    def refresh_stats(self):
        """Refresh index statistics."""
        if self.opensearch_service.is_connected():
            count = self.opensearch_service.get_indexed_count()
            self.stats_label.setText(f"Total indexed images: {count}")
        else:
            self.stats_label.setText("Not connected to OpenSearch")

    def set_directories(self, directories: List[str]):
        """Set the list of directories."""
        self.directories = directories.copy()
        self.dir_list.clear()
        for directory in directories:
            self.dir_list.addItem(directory)
        self._update_buttons_state()

    def get_directories(self) -> List[str]:
        """Get the list of directories."""
        return self.directories.copy()
