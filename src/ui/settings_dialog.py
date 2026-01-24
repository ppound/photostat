"""Settings dialog for OpenSearch connection configuration."""

from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QSpinBox, QCheckBox, QPushButton,
    QLabel, QGroupBox, QMessageBox
)
from PyQt6.QtCore import Qt

from ..services.opensearch_service import OpenSearchService


class SettingsDialog(QDialog):
    """Dialog for configuring OpenSearch connection settings."""

    def __init__(self, config: dict, parent=None):
        """
        Initialize the settings dialog.

        Args:
            config: Current configuration dictionary
            parent: Parent widget
        """
        super().__init__(parent)
        self.config = config.copy()
        self._setup_ui()
        self._load_config()

    def _setup_ui(self):
        """Set up the dialog UI."""
        self.setWindowTitle("Settings")
        self.setMinimumWidth(450)
        self.setModal(True)

        layout = QVBoxLayout(self)

        # OpenSearch Connection Group
        connection_group = QGroupBox("OpenSearch Connection")
        connection_layout = QFormLayout(connection_group)

        # Host
        self.host_edit = QLineEdit()
        self.host_edit.setPlaceholderText("localhost")
        connection_layout.addRow("Host:", self.host_edit)

        # Port
        self.port_spin = QSpinBox()
        self.port_spin.setRange(1, 65535)
        self.port_spin.setValue(9200)
        connection_layout.addRow("Port:", self.port_spin)

        # Use SSL
        self.ssl_check = QCheckBox("Use SSL/TLS")
        connection_layout.addRow("", self.ssl_check)

        # Username
        self.username_edit = QLineEdit()
        self.username_edit.setPlaceholderText("Optional")
        connection_layout.addRow("Username:", self.username_edit)

        # Password
        self.password_edit = QLineEdit()
        self.password_edit.setEchoMode(QLineEdit.EchoMode.Password)
        self.password_edit.setPlaceholderText("Optional")
        connection_layout.addRow("Password:", self.password_edit)

        # Test connection button
        test_layout = QHBoxLayout()
        self.test_btn = QPushButton("Test Connection")
        self.test_btn.clicked.connect(self._test_connection)
        self.test_status = QLabel("")
        test_layout.addWidget(self.test_btn)
        test_layout.addWidget(self.test_status, 1)
        connection_layout.addRow("", test_layout)

        layout.addWidget(connection_group)

        # Index Settings Group
        index_group = QGroupBox("Index Settings")
        index_layout = QFormLayout(index_group)

        # Index name
        self.index_edit = QLineEdit()
        self.index_edit.setPlaceholderText("photovault-images")
        index_layout.addRow("Index Name:", self.index_edit)

        layout.addWidget(index_group)

        # Buttons
        button_layout = QHBoxLayout()
        button_layout.addStretch()

        self.cancel_btn = QPushButton("Cancel")
        self.cancel_btn.clicked.connect(self.reject)
        button_layout.addWidget(self.cancel_btn)

        self.save_btn = QPushButton("Save")
        self.save_btn.clicked.connect(self._save_and_accept)
        self.save_btn.setDefault(True)
        button_layout.addWidget(self.save_btn)

        layout.addLayout(button_layout)

    def _load_config(self):
        """Load current configuration into UI."""
        os_config = self.config.get('opensearch', {})

        self.host_edit.setText(os_config.get('host', 'localhost'))
        self.port_spin.setValue(os_config.get('port', 9200))
        self.ssl_check.setChecked(os_config.get('use_ssl', False))
        self.username_edit.setText(os_config.get('username', ''))
        self.password_edit.setText(os_config.get('password', ''))
        self.index_edit.setText(os_config.get('index_name', 'photovault-images'))

    def _save_and_accept(self):
        """Save configuration and close dialog."""
        # Validate
        if not self.host_edit.text().strip():
            QMessageBox.warning(self, "Validation Error", "Host is required.")
            return

        if not self.index_edit.text().strip():
            QMessageBox.warning(self, "Validation Error", "Index name is required.")
            return

        # Update config
        self.config['opensearch'] = {
            'host': self.host_edit.text().strip(),
            'port': self.port_spin.value(),
            'use_ssl': self.ssl_check.isChecked(),
            'username': self.username_edit.text().strip(),
            'password': self.password_edit.text(),
            'index_name': self.index_edit.text().strip()
        }

        self.accept()

    def _test_connection(self):
        """Test the OpenSearch connection."""
        self.test_status.setText("Testing...")
        self.test_status.setStyleSheet("")
        self.test_btn.setEnabled(False)

        # Force UI update
        from PyQt6.QtWidgets import QApplication
        QApplication.processEvents()

        try:
            service = OpenSearchService(
                host=self.host_edit.text().strip() or 'localhost',
                port=self.port_spin.value(),
                username=self.username_edit.text().strip() or None,
                password=self.password_edit.text() or None,
                use_ssl=self.ssl_check.isChecked(),
                index_name=self.index_edit.text().strip() or 'photovault-images'
            )

            success, message = service.test_connection()

            if success:
                self.test_status.setText("Connected!")
                self.test_status.setStyleSheet("color: green;")
            else:
                self.test_status.setText(f"Failed: {message}")
                self.test_status.setStyleSheet("color: red;")

            service.disconnect()

        except Exception as e:
            self.test_status.setText(f"Error: {str(e)}")
            self.test_status.setStyleSheet("color: red;")

        finally:
            self.test_btn.setEnabled(True)

    def get_config(self) -> dict:
        """Get the updated configuration."""
        return self.config
