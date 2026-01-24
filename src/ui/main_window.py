"""Main application window with tabs."""

import json
import os
from typing import Optional, Dict, Any
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QTabWidget, QStatusBar, QMenuBar, QMenu,
    QMessageBox, QSplitter, QLabel
)
from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QAction

from .search_panel import SearchPanel
from .results_panel import ResultsPanel
from .index_panel import IndexPanel
from .settings_dialog import SettingsDialog
from .charts_panel import ChartsPanel
from ..services.opensearch_service import OpenSearchService


class MainWindow(QMainWindow):
    """Main application window."""

    CONFIG_FILE = "config.json"

    def __init__(self):
        """Initialize the main window."""
        super().__init__()

        self.config = self._load_config()
        self.opensearch_service = OpenSearchService()

        self._setup_ui()
        self._setup_menus()
        self._connect_signals()

        # Auto-connect to OpenSearch
        QTimer.singleShot(100, self._auto_connect)

    def _setup_ui(self):
        """Set up the main window UI."""
        self.setWindowTitle("PhotoVault - EXIF Image Search")
        self.setMinimumSize(1200, 800)

        # Central widget
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        layout.setContentsMargins(0, 0, 0, 0)

        # Tab widget
        self.tabs = QTabWidget()

        # Search & Results tab
        search_results_widget = QWidget()
        search_results_layout = QHBoxLayout(search_results_widget)
        search_results_layout.setContentsMargins(0, 0, 0, 0)

        # Left: Search panel
        self.search_panel = SearchPanel()
        self.search_panel.setMaximumWidth(350)
        search_results_layout.addWidget(self.search_panel)

        # Right: Results panel
        self.results_panel = ResultsPanel()
        search_results_layout.addWidget(self.results_panel, 1)

        self.tabs.addTab(search_results_widget, "Search")

        # Index tab
        self.index_panel = IndexPanel(self.opensearch_service)
        self.tabs.addTab(self.index_panel, "Index")

        # Charts tab
        self.charts_panel = ChartsPanel()
        self.tabs.addTab(self.charts_panel, "Charts")

        layout.addWidget(self.tabs)

        # Status bar
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)

        self.connection_label = QLabel("Disconnected")
        self.connection_label.setStyleSheet("color: red;")
        self.status_bar.addPermanentWidget(self.connection_label)

        # Load saved directories
        self.index_panel.set_directories(self.config.get('directories', []))

    def _setup_menus(self):
        """Set up the menu bar."""
        menubar = self.menuBar()

        # File menu
        file_menu = menubar.addMenu("File")

        settings_action = QAction("Settings...", self)
        settings_action.triggered.connect(self._show_settings)
        file_menu.addAction(settings_action)

        file_menu.addSeparator()

        exit_action = QAction("Exit", self)
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)

        # View menu
        view_menu = menubar.addMenu("View")

        search_action = QAction("Search Tab", self)
        search_action.triggered.connect(lambda: self.tabs.setCurrentIndex(0))
        view_menu.addAction(search_action)

        index_action = QAction("Index Tab", self)
        index_action.triggered.connect(lambda: self.tabs.setCurrentIndex(1))
        view_menu.addAction(index_action)

        charts_action = QAction("Charts Tab", self)
        charts_action.triggered.connect(lambda: self.tabs.setCurrentIndex(2))
        view_menu.addAction(charts_action)

        # Help menu
        help_menu = menubar.addMenu("Help")

        about_action = QAction("About", self)
        about_action.triggered.connect(self._show_about)
        help_menu.addAction(about_action)

    def _connect_signals(self):
        """Connect UI signals."""
        # Search panel signals
        self.search_panel.search_requested.connect(self._on_search)

        # Results panel signals
        self.results_panel.facet_filter_requested.connect(self._on_facet_filter)
        self.results_panel.page_changed.connect(self._on_page_changed)

        # Index panel signals
        self.index_panel.indexing_finished.connect(self._on_indexing_finished)
        self.index_panel.directories_changed.connect(self._save_config)

        # Charts panel signals
        self.charts_panel.refresh_requested.connect(self._refresh_charts)

    def _load_config(self) -> dict:
        """Load configuration from file."""
        default_config = {
            'opensearch': {
                'host': 'localhost',
                'port': 9200,
                'use_ssl': False,
                'username': '',
                'password': '',
                'index_name': 'photovault-images'
            },
            'directories': []
        }

        config_path = os.path.join(os.path.dirname(__file__), '..', '..', self.CONFIG_FILE)

        try:
            if os.path.exists(config_path):
                with open(config_path, 'r') as f:
                    loaded = json.load(f)
                    # Merge with defaults
                    for key in default_config:
                        if key not in loaded:
                            loaded[key] = default_config[key]
                    return loaded
        except Exception as e:
            print(f"Error loading config: {e}")

        return default_config

    def _save_config(self):
        """Save configuration to file."""
        # Update directories from index panel
        self.config['directories'] = self.index_panel.get_directories()

        config_path = os.path.join(os.path.dirname(__file__), '..', '..', self.CONFIG_FILE)

        try:
            with open(config_path, 'w') as f:
                json.dump(self.config, f, indent=2)
        except Exception as e:
            print(f"Error saving config: {e}")

    def _auto_connect(self):
        """Auto-connect to OpenSearch on startup."""
        self._connect_opensearch()

    def _connect_opensearch(self):
        """Connect to OpenSearch using current config."""
        os_config = self.config.get('opensearch', {})

        self.opensearch_service = OpenSearchService(
            host=os_config.get('host', 'localhost'),
            port=os_config.get('port', 9200),
            username=os_config.get('username') or None,
            password=os_config.get('password') or None,
            use_ssl=os_config.get('use_ssl', False),
            index_name=os_config.get('index_name', 'photovault-images')
        )

        # Update index panel's service reference
        self.index_panel.opensearch_service = self.opensearch_service

        if self.opensearch_service.connect():
            self.connection_label.setText("Connected")
            self.connection_label.setStyleSheet("color: green;")
            self.status_bar.showMessage("Connected to OpenSearch", 3000)

            # Ensure index exists
            self.opensearch_service.ensure_index()

            # Refresh stats
            self.index_panel.refresh_stats()

            # Initial search to show all results
            self._on_search("", {})
        else:
            self.connection_label.setText("Disconnected")
            self.connection_label.setStyleSheet("color: red;")
            self.status_bar.showMessage("Failed to connect to OpenSearch", 5000)

    def _show_settings(self):
        """Show the settings dialog."""
        dialog = SettingsDialog(self.config, self)
        if dialog.exec():
            self.config = dialog.get_config()
            self._save_config()
            self._connect_opensearch()

    def _show_about(self):
        """Show the about dialog."""
        QMessageBox.about(
            self,
            "About PhotoVault",
            "PhotoVault - EXIF Image Search\n\n"
            "A desktop application for indexing and searching\n"
            "image EXIF metadata using OpenSearch.\n\n"
            "Built with PyQt6, pyexiftool, and opensearch-py."
        )

    def _on_search(self, query: str, filters: Dict[str, Any]):
        """Handle search request."""
        if not self.opensearch_service.is_connected():
            QMessageBox.warning(
                self,
                "Not Connected",
                "Please connect to OpenSearch first."
            )
            return

        self.status_bar.showMessage("Searching...")

        sort_field, sort_order = self.results_panel.get_sort()

        results = self.opensearch_service.search(
            query_text=query if query else None,
            filters=filters,
            from_=0,
            size=self.results_panel.page_size,
            sort_by=sort_field,
            sort_order=sort_order
        )

        self.results_panel.set_results(
            results['hits'],
            results['total'],
            results['aggregations'],
            page=0
        )

        # Update search panel combo options
        self.search_panel.update_facet_options(results['aggregations'])

        self.status_bar.showMessage(f"Found {results['total']} results", 3000)

        # Store current search for pagination
        self._current_query = query
        self._current_filters = filters

        # Update charts with all data
        self._update_charts(results['aggregations'], results['hits'])

    def _on_facet_filter(self, field: str, value: str):
        """Handle facet filter click."""
        # Set the filter in search panel and trigger search
        self.search_panel.set_filter(field, value)

    def _on_page_changed(self, page: int):
        """Handle pagination."""
        if not self.opensearch_service.is_connected():
            return

        query = getattr(self, '_current_query', '')
        filters = getattr(self, '_current_filters', {})
        sort_field, sort_order = self.results_panel.get_sort()

        results = self.opensearch_service.search(
            query_text=query if query else None,
            filters=filters,
            from_=page * self.results_panel.page_size,
            size=self.results_panel.page_size,
            sort_by=sort_field,
            sort_order=sort_order
        )

        self.results_panel.set_results(
            results['hits'],
            results['total'],
            results['aggregations'],
            page=page
        )

    def _on_indexing_finished(self):
        """Handle indexing completion."""
        # Refresh search results
        self._on_search(
            getattr(self, '_current_query', ''),
            getattr(self, '_current_filters', {})
        )

    def _refresh_charts(self):
        """Refresh charts with current data."""
        if not self.opensearch_service.is_connected():
            return

        # Get all data for charts (larger size for better histograms)
        results = self.opensearch_service.search(
            query_text=None,
            filters={},
            from_=0,
            size=1000,  # Get more data for charts
            sort_by="date_taken",
            sort_order="desc"
        )

        self._update_charts(results['aggregations'], results['hits'])

    def _update_charts(self, aggregations, hits):
        """Update charts panel with data."""
        self.charts_panel.update_charts(aggregations, hits)

    def closeEvent(self, event):
        """Handle window close."""
        self._save_config()
        if self.opensearch_service:
            self.opensearch_service.disconnect()
        event.accept()
