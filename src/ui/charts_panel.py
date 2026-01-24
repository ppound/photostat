"""Charts panel for visualizing EXIF metadata statistics."""

from typing import Dict, Any, List, Optional
from datetime import datetime
import matplotlib
matplotlib.use('QtAgg')

from matplotlib.backends.backend_qtagg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure
import matplotlib.pyplot as plt
import numpy as np

from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QComboBox,
    QPushButton, QLabel, QGroupBox, QScrollArea,
    QSplitter, QFrame, QGridLayout, QSizePolicy
)
from PyQt6.QtCore import Qt, pyqtSignal


class ChartCanvas(FigureCanvas):
    """Matplotlib canvas for embedding charts in Qt."""

    def __init__(self, parent=None, width=5, height=4, dpi=100):
        self.fig = Figure(figsize=(width, height), dpi=dpi)
        self.axes = self.fig.add_subplot(111)
        super().__init__(self.fig)
        self.setParent(parent)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)

        # Style
        self.fig.patch.set_facecolor('#ffffff')
        self.axes.set_facecolor('#f8f8f8')

    def clear(self):
        """Clear the chart."""
        self.axes.clear()
        self.draw()


class BarChartWidget(QWidget):
    """Widget for displaying bar charts."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.title = title
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        self.canvas = ChartCanvas(self, width=6, height=4)
        layout.addWidget(self.canvas)

    def update_chart(self, labels: List[str], values: List[int], color: str = '#0078d4'):
        """Update the bar chart with new data."""
        self.canvas.axes.clear()

        if not labels or not values:
            self.canvas.axes.text(0.5, 0.5, 'No data available',
                                   ha='center', va='center', fontsize=12)
            self.canvas.axes.set_xlim(0, 1)
            self.canvas.axes.set_ylim(0, 1)
            self.canvas.draw()
            return

        # Limit to top 15 for readability
        if len(labels) > 15:
            labels = labels[:15]
            values = values[:15]

        y_pos = np.arange(len(labels))

        bars = self.canvas.axes.barh(y_pos, values, color=color, edgecolor='white')
        self.canvas.axes.set_yticks(y_pos)
        self.canvas.axes.set_yticklabels(labels, fontsize=9)
        self.canvas.axes.invert_yaxis()
        self.canvas.axes.set_xlabel('Count', fontsize=10)
        self.canvas.axes.set_title(self.title, fontsize=12, fontweight='bold')

        # Add value labels on bars
        for bar, val in zip(bars, values):
            self.canvas.axes.text(bar.get_width() + 0.3, bar.get_y() + bar.get_height()/2,
                                   str(val), va='center', fontsize=8)

        self.canvas.fig.tight_layout()
        self.canvas.draw()


class PieChartWidget(QWidget):
    """Widget for displaying pie charts."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.title = title
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        self.canvas = ChartCanvas(self, width=5, height=4)
        layout.addWidget(self.canvas)

    def update_chart(self, labels: List[str], values: List[int], colors: Optional[List[str]] = None):
        """Update the pie chart with new data."""
        self.canvas.axes.clear()

        if not labels or not values:
            self.canvas.axes.text(0.5, 0.5, 'No data available',
                                   ha='center', va='center', fontsize=12)
            self.canvas.draw()
            return

        # Limit to top 8 and group rest as "Other"
        if len(labels) > 8:
            other_sum = sum(values[8:])
            labels = labels[:8] + ['Other']
            values = values[:8] + [other_sum]

        if colors is None:
            colors = plt.cm.Set3(np.linspace(0, 1, len(labels)))

        wedges, texts, autotexts = self.canvas.axes.pie(
            values, labels=labels, autopct='%1.1f%%',
            colors=colors, startangle=90,
            textprops={'fontsize': 8}
        )

        self.canvas.axes.set_title(self.title, fontsize=12, fontweight='bold')
        self.canvas.fig.tight_layout()
        self.canvas.draw()


class HistogramWidget(QWidget):
    """Widget for displaying histograms."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.title = title
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        self.canvas = ChartCanvas(self, width=6, height=4)
        layout.addWidget(self.canvas)

    def update_chart(self, values: List[float], bins: int = 20,
                     xlabel: str = '', color: str = '#0078d4'):
        """Update the histogram with new data."""
        self.canvas.axes.clear()

        if not values:
            self.canvas.axes.text(0.5, 0.5, 'No data available',
                                   ha='center', va='center', fontsize=12)
            self.canvas.draw()
            return

        self.canvas.axes.hist(values, bins=bins, color=color, edgecolor='white', alpha=0.8)
        self.canvas.axes.set_xlabel(xlabel, fontsize=10)
        self.canvas.axes.set_ylabel('Count', fontsize=10)
        self.canvas.axes.set_title(self.title, fontsize=12, fontweight='bold')

        self.canvas.fig.tight_layout()
        self.canvas.draw()


class TimelineWidget(QWidget):
    """Widget for displaying timeline charts."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.title = title
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        self.canvas = ChartCanvas(self, width=8, height=4)
        layout.addWidget(self.canvas)

    def update_chart(self, dates: List[str], counts: List[int], color: str = '#0078d4'):
        """Update the timeline chart with new data."""
        self.canvas.axes.clear()

        if not dates or not counts:
            self.canvas.axes.text(0.5, 0.5, 'No data available',
                                   ha='center', va='center', fontsize=12)
            self.canvas.draw()
            return

        x_pos = np.arange(len(dates))

        self.canvas.axes.bar(x_pos, counts, color=color, edgecolor='white')
        self.canvas.axes.set_xticks(x_pos)
        self.canvas.axes.set_xticklabels(dates, rotation=45, ha='right', fontsize=8)
        self.canvas.axes.set_ylabel('Count', fontsize=10)
        self.canvas.axes.set_title(self.title, fontsize=12, fontweight='bold')

        self.canvas.fig.tight_layout()
        self.canvas.draw()


class MapWidget(QWidget):
    """Widget for displaying GPS locations on a scatter plot map."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.title = title
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        self.canvas = ChartCanvas(self, width=6, height=5)
        layout.addWidget(self.canvas)

    def update_chart(self, locations: List[tuple], labels: Optional[List[str]] = None):
        """Update the map with GPS locations. locations = [(lat, lon), ...]"""
        self.canvas.axes.clear()

        if not locations:
            self.canvas.axes.text(0.5, 0.5, 'No GPS data available',
                                   ha='center', va='center', fontsize=12)
            self.canvas.draw()
            return

        lats = [loc[0] for loc in locations]
        lons = [loc[1] for loc in locations]

        self.canvas.axes.scatter(lons, lats, c='#0078d4', s=100, alpha=0.7, edgecolors='white')

        # Add labels if provided
        if labels:
            for i, (lat, lon) in enumerate(locations):
                if i < len(labels):
                    self.canvas.axes.annotate(labels[i], (lon, lat),
                                               xytext=(5, 5), textcoords='offset points',
                                               fontsize=7)

        self.canvas.axes.set_xlabel('Longitude', fontsize=10)
        self.canvas.axes.set_ylabel('Latitude', fontsize=10)
        self.canvas.axes.set_title(self.title, fontsize=12, fontweight='bold')
        self.canvas.axes.grid(True, alpha=0.3)

        self.canvas.fig.tight_layout()
        self.canvas.draw()


class ChartsPanel(QWidget):
    """Panel for displaying various EXIF data charts."""

    refresh_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)

        # Toolbar
        toolbar = QHBoxLayout()

        toolbar.addWidget(QLabel("Chart View:"))

        self.view_combo = QComboBox()
        self.view_combo.addItem("Overview (All Charts)", "overview")
        self.view_combo.addItem("Camera Statistics", "camera")
        self.view_combo.addItem("Lens Statistics", "lens")
        self.view_combo.addItem("Exposure Settings", "exposure")
        self.view_combo.addItem("Timeline", "timeline")
        self.view_combo.addItem("Locations", "locations")
        self.view_combo.currentIndexChanged.connect(self._on_view_changed)
        toolbar.addWidget(self.view_combo)

        toolbar.addStretch()

        self.refresh_btn = QPushButton("Refresh")
        self.refresh_btn.clicked.connect(self.refresh_requested.emit)
        toolbar.addWidget(self.refresh_btn)

        layout.addLayout(toolbar)

        # Scroll area for charts
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)

        self.charts_container = QWidget()
        self.charts_layout = QGridLayout(self.charts_container)
        self.charts_layout.setSpacing(15)

        scroll.setWidget(self.charts_container)
        layout.addWidget(scroll)

        # Create all chart widgets
        self._create_charts()

    def _create_charts(self):
        """Create all chart widgets."""
        # Camera charts
        self.camera_make_chart = BarChartWidget("Camera Makes")
        self.camera_model_chart = BarChartWidget("Camera Models")

        # Lens chart
        self.lens_chart = BarChartWidget("Lenses")

        # Exposure charts
        self.iso_chart = BarChartWidget("ISO Distribution")
        self.aperture_chart = HistogramWidget("Aperture Distribution")
        self.focal_length_chart = HistogramWidget("Focal Length Distribution")

        # Pie charts
        self.file_type_pie = PieChartWidget("File Types")
        self.camera_make_pie = PieChartWidget("Camera Makes")

        # Timeline
        self.timeline_chart = TimelineWidget("Photos Over Time")

        # Map
        self.map_chart = MapWidget("Photo Locations")

        # Default to overview
        self._show_overview()

    def _clear_layout(self):
        """Clear the charts layout."""
        while self.charts_layout.count():
            item = self.charts_layout.takeAt(0)
            if item.widget():
                item.widget().setParent(None)

    def _on_view_changed(self):
        """Handle view selection change."""
        view = self.view_combo.currentData()

        if view == "overview":
            self._show_overview()
        elif view == "camera":
            self._show_camera_view()
        elif view == "lens":
            self._show_lens_view()
        elif view == "exposure":
            self._show_exposure_view()
        elif view == "timeline":
            self._show_timeline_view()
        elif view == "locations":
            self._show_locations_view()

    def _show_overview(self):
        """Show overview with main charts."""
        self._clear_layout()
        self.charts_layout.addWidget(self.camera_make_chart, 0, 0)
        self.charts_layout.addWidget(self.camera_model_chart, 0, 1)
        self.charts_layout.addWidget(self.file_type_pie, 1, 0)
        self.charts_layout.addWidget(self.timeline_chart, 1, 1)

    def _show_camera_view(self):
        """Show camera-focused charts."""
        self._clear_layout()
        self.charts_layout.addWidget(self.camera_make_chart, 0, 0)
        self.charts_layout.addWidget(self.camera_make_pie, 0, 1)
        self.charts_layout.addWidget(self.camera_model_chart, 1, 0, 1, 2)

    def _show_lens_view(self):
        """Show lens-focused charts."""
        self._clear_layout()
        self.charts_layout.addWidget(self.lens_chart, 0, 0, 1, 2)
        self.charts_layout.addWidget(self.focal_length_chart, 1, 0, 1, 2)

    def _show_exposure_view(self):
        """Show exposure-focused charts."""
        self._clear_layout()
        self.charts_layout.addWidget(self.iso_chart, 0, 0)
        self.charts_layout.addWidget(self.aperture_chart, 0, 1)
        self.charts_layout.addWidget(self.focal_length_chart, 1, 0, 1, 2)

    def _show_timeline_view(self):
        """Show timeline chart."""
        self._clear_layout()
        self.charts_layout.addWidget(self.timeline_chart, 0, 0)

    def _show_locations_view(self):
        """Show locations map."""
        self._clear_layout()
        self.charts_layout.addWidget(self.map_chart, 0, 0)

    def update_charts(self, aggregations: Dict[str, Any], raw_data: Optional[List] = None):
        """Update all charts with aggregation data."""

        # Camera Make bar chart
        make_buckets = aggregations.get('camera_make', {}).get('buckets', [])
        make_labels = [b['key'] for b in make_buckets]
        make_values = [b['doc_count'] for b in make_buckets]
        self.camera_make_chart.update_chart(make_labels, make_values, '#2196F3')
        self.camera_make_pie.update_chart(make_labels, make_values)

        # Camera Model bar chart
        model_buckets = aggregations.get('camera_model', {}).get('buckets', [])
        model_labels = [b['key'] for b in model_buckets]
        model_values = [b['doc_count'] for b in model_buckets]
        self.camera_model_chart.update_chart(model_labels, model_values, '#4CAF50')

        # Lens bar chart
        lens_buckets = aggregations.get('lens_model', {}).get('buckets', [])
        lens_labels = [b['key'] for b in lens_buckets]
        lens_values = [b['doc_count'] for b in lens_buckets]
        self.lens_chart.update_chart(lens_labels, lens_values, '#FF9800')

        # File type pie chart
        type_buckets = aggregations.get('file_type', {}).get('buckets', [])
        type_labels = [b['key'].upper() for b in type_buckets]
        type_values = [b['doc_count'] for b in type_buckets]
        self.file_type_pie.update_chart(type_labels, type_values)

        # ISO bar chart
        iso_buckets = aggregations.get('iso_ranges', {}).get('buckets', [])
        iso_labels = [b['key'] for b in iso_buckets if b['doc_count'] > 0]
        iso_values = [b['doc_count'] for b in iso_buckets if b['doc_count'] > 0]
        self.iso_chart.update_chart(iso_labels, iso_values, '#9C27B0')

        # Timeline chart
        date_buckets = aggregations.get('year_month', {}).get('buckets', [])
        date_labels = [b['key_as_string'] for b in date_buckets]
        date_values = [b['doc_count'] for b in date_buckets]
        self.timeline_chart.update_chart(date_labels, date_values, '#00BCD4')

        # Process raw data for histograms and map
        if raw_data:
            # Aperture histogram
            apertures = [img.aperture for img in raw_data if img.aperture is not None]
            self.aperture_chart.update_chart(apertures, bins=15, xlabel='Aperture (f-stop)', color='#E91E63')

            # Focal length histogram
            focals = [img.focal_length for img in raw_data if img.focal_length is not None]
            self.focal_length_chart.update_chart(focals, bins=20, xlabel='Focal Length (mm)', color='#673AB7')

            # GPS locations
            locations = [(img.gps_latitude, img.gps_longitude)
                        for img in raw_data
                        if img.gps_latitude is not None and img.gps_longitude is not None]
            labels = [img.file_name for img in raw_data
                     if img.gps_latitude is not None and img.gps_longitude is not None]
            self.map_chart.update_chart(locations, labels)
