"""Background indexing worker using QThread."""

from typing import List, Optional
from PyQt6.QtCore import QThread, pyqtSignal

from .exif_service import ExifService
from .opensearch_service import OpenSearchService
from ..models.image_metadata import ImageMetadata


class IndexerWorker(QThread):
    """Background worker for indexing image files."""

    # Signals
    progress_updated = pyqtSignal(int, int, str)  # current, total, current_file
    indexing_complete = pyqtSignal(int, int)  # success_count, error_count
    error_occurred = pyqtSignal(str)  # error message

    BATCH_SIZE = 50

    def __init__(
        self,
        directories: List[str],
        opensearch_service: OpenSearchService,
        recursive: bool = True,
        parent=None
    ):
        """
        Initialize the indexer worker.

        Args:
            directories: List of directory paths to index
            opensearch_service: OpenSearch service instance
            recursive: Whether to scan directories recursively
            parent: Parent QObject
        """
        super().__init__(parent)
        self.directories = directories
        self.opensearch_service = opensearch_service
        self.recursive = recursive
        self._stop_requested = False

    def stop(self):
        """Request the worker to stop."""
        self._stop_requested = True

    def run(self):
        """Main worker thread execution."""
        self._stop_requested = False
        total_success = 0
        total_errors = 0

        try:
            # Ensure index exists
            if not self.opensearch_service.ensure_index():
                self.error_occurred.emit("Failed to create/verify index")
                return

            # Collect all files to index
            all_files = []
            for directory in self.directories:
                if self._stop_requested:
                    break

                with ExifService() as exif_service:
                    files = exif_service.scan_directory(directory, self.recursive)
                    all_files.extend(files)

            if self._stop_requested:
                self.indexing_complete.emit(total_success, total_errors)
                return

            total_files = len(all_files)
            if total_files == 0:
                self.indexing_complete.emit(0, 0)
                return

            # Process files in batches
            with ExifService() as exif_service:
                for i in range(0, total_files, self.BATCH_SIZE):
                    if self._stop_requested:
                        break

                    batch = all_files[i:i + self.BATCH_SIZE]
                    current_file = batch[0] if batch else ""

                    # Update progress
                    self.progress_updated.emit(i, total_files, current_file)

                    # Extract metadata for batch
                    metadata_list = exif_service.extract_metadata_batch(batch)

                    if metadata_list:
                        # Index batch
                        success, errors = self.opensearch_service.index_documents_bulk(metadata_list)
                        total_success += success
                        total_errors += errors

            # Refresh index to make documents searchable
            self.opensearch_service.refresh_index()

            # Final progress update
            self.progress_updated.emit(total_files, total_files, "")
            self.indexing_complete.emit(total_success, total_errors)

        except Exception as e:
            self.error_occurred.emit(str(e))
            self.indexing_complete.emit(total_success, total_errors)


class DirectoryScanWorker(QThread):
    """Background worker for scanning directories."""

    # Signals
    scan_complete = pyqtSignal(list)  # list of file paths
    error_occurred = pyqtSignal(str)

    def __init__(
        self,
        directory: str,
        recursive: bool = True,
        parent=None
    ):
        """Initialize the scanner worker."""
        super().__init__(parent)
        self.directory = directory
        self.recursive = recursive

    def run(self):
        """Scan directory for image files."""
        try:
            with ExifService() as exif_service:
                files = exif_service.scan_directory(self.directory, self.recursive)
                self.scan_complete.emit(files)
        except Exception as e:
            self.error_occurred.emit(str(e))
            self.scan_complete.emit([])
