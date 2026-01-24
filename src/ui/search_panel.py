"""Search panel with field filters."""

from typing import Dict, Any, Optional
from datetime import datetime, date
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QComboBox, QSpinBox, QDoubleSpinBox,
    QPushButton, QLabel, QGroupBox, QDateEdit, QScrollArea
)
from PyQt6.QtCore import Qt, pyqtSignal, QDate


class SearchPanel(QWidget):
    """Panel for search interface with field filters."""

    search_requested = pyqtSignal(str, dict)  # query_text, filters

    def __init__(self, parent=None):
        """Initialize the search panel."""
        super().__init__(parent)
        self._setup_ui()

    def _setup_ui(self):
        """Set up the panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(15, 15, 15, 15)
        layout.setSpacing(10)

        # Main search box
        search_group = QGroupBox("Search")
        search_layout = QVBoxLayout(search_group)

        search_row = QHBoxLayout()
        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search across all fields...")
        self.search_edit.returnPressed.connect(self._on_search)
        search_row.addWidget(self.search_edit)

        self.search_btn = QPushButton("Search")
        self.search_btn.clicked.connect(self._on_search)
        search_row.addWidget(self.search_btn)

        search_layout.addLayout(search_row)
        layout.addWidget(search_group)

        # Filters in scrollable area
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)

        filters_widget = QWidget()
        filters_layout = QVBoxLayout(filters_widget)
        filters_layout.setContentsMargins(0, 0, 0, 0)
        filters_layout.setSpacing(10)

        # Camera filters
        camera_group = QGroupBox("Camera")
        camera_layout = QFormLayout(camera_group)

        self.make_combo = QComboBox()
        self.make_combo.setEditable(True)
        self.make_combo.setPlaceholderText("Any")
        self.make_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        camera_layout.addRow("Make:", self.make_combo)

        self.model_combo = QComboBox()
        self.model_combo.setEditable(True)
        self.model_combo.setPlaceholderText("Any")
        self.model_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        camera_layout.addRow("Model:", self.model_combo)

        self.lens_combo = QComboBox()
        self.lens_combo.setEditable(True)
        self.lens_combo.setPlaceholderText("Any")
        self.lens_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        camera_layout.addRow("Lens:", self.lens_combo)

        filters_layout.addWidget(camera_group)

        # Date filters
        date_group = QGroupBox("Date Taken")
        date_layout = QFormLayout(date_group)

        self.date_from = QDateEdit()
        self.date_from.setCalendarPopup(True)
        self.date_from.setDate(QDate(2000, 1, 1))
        self.date_from.setSpecialValueText("Any")
        date_layout.addRow("From:", self.date_from)

        self.date_to = QDateEdit()
        self.date_to.setCalendarPopup(True)
        self.date_to.setDate(QDate.currentDate())
        self.date_to.setSpecialValueText("Any")
        date_layout.addRow("To:", self.date_to)

        self.date_enabled = QPushButton("Enable Date Filter")
        self.date_enabled.setCheckable(True)
        self.date_enabled.toggled.connect(self._on_date_filter_toggled)
        date_layout.addRow("", self.date_enabled)

        # Initially disable date inputs
        self.date_from.setEnabled(False)
        self.date_to.setEnabled(False)

        filters_layout.addWidget(date_group)

        # Exposure filters
        exposure_group = QGroupBox("Exposure Settings")
        exposure_layout = QFormLayout(exposure_group)

        # ISO range
        iso_row = QHBoxLayout()
        self.iso_min = QSpinBox()
        self.iso_min.setRange(0, 1000000)
        self.iso_min.setSpecialValueText("Min")
        iso_row.addWidget(self.iso_min)
        iso_row.addWidget(QLabel("-"))
        self.iso_max = QSpinBox()
        self.iso_max.setRange(0, 1000000)
        self.iso_max.setSpecialValueText("Max")
        iso_row.addWidget(self.iso_max)
        exposure_layout.addRow("ISO:", iso_row)

        # Aperture range
        aperture_row = QHBoxLayout()
        self.aperture_min = QDoubleSpinBox()
        self.aperture_min.setRange(0, 100)
        self.aperture_min.setDecimals(1)
        self.aperture_min.setSpecialValueText("Min")
        aperture_row.addWidget(self.aperture_min)
        aperture_row.addWidget(QLabel("-"))
        self.aperture_max = QDoubleSpinBox()
        self.aperture_max.setRange(0, 100)
        self.aperture_max.setDecimals(1)
        self.aperture_max.setSpecialValueText("Max")
        aperture_row.addWidget(self.aperture_max)
        exposure_layout.addRow("Aperture (f/):", aperture_row)

        # Focal length range
        focal_row = QHBoxLayout()
        self.focal_min = QSpinBox()
        self.focal_min.setRange(0, 10000)
        self.focal_min.setSpecialValueText("Min")
        focal_row.addWidget(self.focal_min)
        focal_row.addWidget(QLabel("-"))
        self.focal_max = QSpinBox()
        self.focal_max.setRange(0, 10000)
        self.focal_max.setSpecialValueText("Max")
        focal_row.addWidget(self.focal_max)
        exposure_layout.addRow("Focal Length (mm):", focal_row)

        filters_layout.addWidget(exposure_group)

        # File type filter
        file_group = QGroupBox("File")
        file_layout = QFormLayout(file_group)

        self.file_type_combo = QComboBox()
        self.file_type_combo.addItem("Any", "")
        self.file_type_combo.addItem("JPEG", "jpg")
        self.file_type_combo.addItem("PNG", "png")
        self.file_type_combo.addItem("TIFF", "tiff")
        self.file_type_combo.addItem("RAW (CR2)", "cr2")
        self.file_type_combo.addItem("RAW (CR3)", "cr3")
        self.file_type_combo.addItem("RAW (NEF)", "nef")
        self.file_type_combo.addItem("RAW (ARW)", "arw")
        self.file_type_combo.addItem("RAW (DNG)", "dng")
        self.file_type_combo.addItem("HEIC", "heic")
        file_layout.addRow("File Type:", self.file_type_combo)

        filters_layout.addWidget(file_group)

        filters_layout.addStretch()

        scroll.setWidget(filters_widget)
        layout.addWidget(scroll, 1)

        # Clear and search buttons
        btn_layout = QHBoxLayout()

        self.clear_btn = QPushButton("Clear All Filters")
        self.clear_btn.clicked.connect(self.clear_filters)
        btn_layout.addWidget(self.clear_btn)

        btn_layout.addStretch()

        layout.addLayout(btn_layout)

    def _on_date_filter_toggled(self, checked: bool):
        """Handle date filter toggle."""
        self.date_from.setEnabled(checked)
        self.date_to.setEnabled(checked)
        if checked:
            self.date_enabled.setText("Disable Date Filter")
        else:
            self.date_enabled.setText("Enable Date Filter")

    def _on_search(self):
        """Emit search signal with current filters."""
        query = self.search_edit.text().strip()
        filters = self.get_filters()
        self.search_requested.emit(query, filters)

    def get_filters(self) -> Dict[str, Any]:
        """Get current filter values."""
        filters = {}

        # Camera filters
        if self.make_combo.currentText().strip():
            filters['camera_make'] = self.make_combo.currentText().strip()

        if self.model_combo.currentText().strip():
            filters['camera_model'] = self.model_combo.currentText().strip()

        if self.lens_combo.currentText().strip():
            filters['lens_model'] = self.lens_combo.currentText().strip()

        # Date filters
        if self.date_enabled.isChecked():
            filters['date_from'] = self.date_from.date().toString(Qt.DateFormat.ISODate)
            filters['date_to'] = self.date_to.date().toString(Qt.DateFormat.ISODate)

        # Exposure filters
        if self.iso_min.value() > 0:
            filters['iso_min'] = self.iso_min.value()
        if self.iso_max.value() > 0:
            filters['iso_max'] = self.iso_max.value()

        if self.aperture_min.value() > 0:
            filters['aperture_min'] = self.aperture_min.value()
        if self.aperture_max.value() > 0:
            filters['aperture_max'] = self.aperture_max.value()

        if self.focal_min.value() > 0:
            filters['focal_min'] = self.focal_min.value()
        if self.focal_max.value() > 0:
            filters['focal_max'] = self.focal_max.value()

        # File type
        file_type = self.file_type_combo.currentData()
        if file_type:
            filters['file_type'] = file_type

        return filters

    def set_filter(self, field: str, value: Any):
        """Set a specific filter value."""
        if field == 'camera_make':
            self.make_combo.setCurrentText(str(value))
        elif field == 'camera_model':
            self.model_combo.setCurrentText(str(value))
        elif field == 'lens_model':
            self.lens_combo.setCurrentText(str(value))
        elif field == 'file_type':
            index = self.file_type_combo.findData(value)
            if index >= 0:
                self.file_type_combo.setCurrentIndex(index)

        # Trigger search after setting filter
        self._on_search()

    def clear_filters(self):
        """Clear all filter values."""
        self.search_edit.clear()

        # Clear combos
        self.make_combo.setCurrentIndex(-1)
        self.make_combo.setEditText("")
        self.model_combo.setCurrentIndex(-1)
        self.model_combo.setEditText("")
        self.lens_combo.setCurrentIndex(-1)
        self.lens_combo.setEditText("")

        # Clear date filters
        self.date_enabled.setChecked(False)
        self.date_from.setDate(QDate(2000, 1, 1))
        self.date_to.setDate(QDate.currentDate())

        # Clear exposure filters
        self.iso_min.setValue(0)
        self.iso_max.setValue(0)
        self.aperture_min.setValue(0)
        self.aperture_max.setValue(0)
        self.focal_min.setValue(0)
        self.focal_max.setValue(0)

        # Clear file type
        self.file_type_combo.setCurrentIndex(0)

    def update_facet_options(self, aggregations: Dict[str, Any]):
        """Update combo boxes with facet values from search results."""
        # Update camera make options
        self._update_combo_options(
            self.make_combo,
            aggregations.get('camera_make', {}).get('buckets', [])
        )

        # Update camera model options
        self._update_combo_options(
            self.model_combo,
            aggregations.get('camera_model', {}).get('buckets', [])
        )

        # Update lens options
        self._update_combo_options(
            self.lens_combo,
            aggregations.get('lens_model', {}).get('buckets', [])
        )

    def _update_combo_options(self, combo: QComboBox, buckets: list):
        """Update a combo box with facet bucket values."""
        current = combo.currentText()
        combo.clear()

        for bucket in buckets:
            key = bucket.get('key', '')
            count = bucket.get('doc_count', 0)
            if key:
                combo.addItem(f"{key} ({count})", key)

        # Restore selection if it still exists
        if current:
            combo.setEditText(current)
