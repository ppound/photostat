"""Main application class for PhotoStat."""

import sys
import os
from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import Qt

from .ui.main_window import MainWindow


class PhotoStatApp:
    """Main application class."""

    def __init__(self):
        """Initialize the application."""
        self.app = QApplication(sys.argv)
        self.app.setApplicationName("PhotoStat")
        self.app.setOrganizationName("PhotoStat")

        # Load stylesheet
        self._load_stylesheet()

        # Create main window
        self.main_window = MainWindow()

    def _load_stylesheet(self):
        """Load the application stylesheet."""
        style_path = os.path.join(
            os.path.dirname(__file__),
            '..',
            'resources',
            'styles.qss'
        )

        try:
            if os.path.exists(style_path):
                with open(style_path, 'r') as f:
                    self.app.setStyleSheet(f.read())
        except Exception as e:
            print(f"Error loading stylesheet: {e}")

    def run(self) -> int:
        """Run the application."""
        self.main_window.show()
        return self.app.exec()


def main():
    """Application entry point."""
    app = PhotoStatApp()
    sys.exit(app.run())


if __name__ == '__main__':
    main()
