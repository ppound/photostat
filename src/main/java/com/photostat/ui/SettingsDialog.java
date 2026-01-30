package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dialog for configuring application settings.
 */
public class SettingsDialog extends Dialog<Boolean> {

    private final ConfigService configService;
    private final OpenSearchService openSearchService;

    private TextField hostField;
    private TextField portField;
    private CheckBox sslCheckbox;
    private TextField usernameField;
    private PasswordField passwordField;
    private TextField indexNameField;
    private TextField exifToolPathField;
    private Spinner<Integer> batchSizeSpinner;
    private Spinner<Integer> thumbnailSizeSpinner;
    private Spinner<Integer> resultsPerPageSpinner;

    // Logging settings
    private CheckBox loggingEnabledCheckbox;
    private ComboBox<String> logLevelCombo;

    private Label connectionStatusLabel;

    public SettingsDialog() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();

        setTitle("Settings");
        setHeaderText("Configure PhotoStat Settings");
        setResizable(true);

        // Dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Create tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab openSearchTab = new Tab("OpenSearch");
        openSearchTab.setContent(createOpenSearchPane());

        Tab indexingTab = new Tab("Indexing");
        indexingTab.setContent(createIndexingPane());

        Tab uiTab = new Tab("User Interface");
        uiTab.setContent(createUIPane());

        Tab loggingTab = new Tab("Logging");
        loggingTab.setContent(createLoggingPane());

        tabPane.getTabs().addAll(openSearchTab, indexingTab, uiTab, loggingTab);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(450);
        content.setPrefHeight(350);
        content.getChildren().add(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getDialogPane().setContent(content);

        // Load current settings
        loadSettings();

        // Result converter
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                saveSettings();
                return true;
            }
            return false;
        });
    }

    private VBox createOpenSearchPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Host
        grid.add(new Label("Host:"), 0, row);
        hostField = new TextField();
        hostField.setPromptText("localhost");
        hostField.setPrefWidth(200);
        grid.add(hostField, 1, row++);

        // Port
        grid.add(new Label("Port:"), 0, row);
        portField = new TextField();
        portField.setPromptText("9200");
        portField.setPrefWidth(80);
        grid.add(portField, 1, row++);

        // SSL
        grid.add(new Label("Use SSL:"), 0, row);
        sslCheckbox = new CheckBox("Enable HTTPS");
        grid.add(sslCheckbox, 1, row++);

        // Username
        grid.add(new Label("Username:"), 0, row);
        usernameField = new TextField();
        usernameField.setPromptText("(optional)");
        grid.add(usernameField, 1, row++);

        // Password
        grid.add(new Label("Password:"), 0, row);
        passwordField = new PasswordField();
        passwordField.setPromptText("(optional)");
        grid.add(passwordField, 1, row++);

        // Index name
        grid.add(new Label("Index Name:"), 0, row);
        indexNameField = new TextField();
        indexNameField.setPromptText("photostat");
        grid.add(indexNameField, 1, row++);

        // Test connection button
        Button testButton = new Button("Test Connection");
        testButton.setOnAction(e -> testConnection());

        connectionStatusLabel = new Label("");
        connectionStatusLabel.setWrapText(true);

        HBox testBox = new HBox(10, testButton, connectionStatusLabel);
        HBox.setHgrow(connectionStatusLabel, Priority.ALWAYS);

        pane.getChildren().addAll(grid, new Separator(), testBox);

        return pane;
    }

    private VBox createIndexingPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Batch size
        grid.add(new Label("Batch Size:"), 0, row);
        batchSizeSpinner = new Spinner<>(10, 500, 50, 10);
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(100);
        grid.add(batchSizeSpinner, 1, row);
        grid.add(new Label("(documents per batch)"), 2, row++);

        // ExifTool path
        grid.add(new Label("ExifTool Path:"), 0, row);
        exifToolPathField = new TextField();
        exifToolPathField.setPromptText("exiftool");
        exifToolPathField.setPrefWidth(200);
        grid.add(exifToolPathField, 1, row);

        Button browseExifTool = new Button("...");
        browseExifTool.setOnAction(e -> browseForExifTool());
        grid.add(browseExifTool, 2, row++);

        // Info label
        Label infoLabel = new Label(
                "ExifTool is required for extracting metadata from RAW files (CR2, NEF, ARW, etc.). " +
                        "Download from: https://exiftool.org/"
        );
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");

        pane.getChildren().addAll(grid, new Separator(), infoLabel);

        return pane;
    }

    private VBox createUIPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Thumbnail size
        grid.add(new Label("Thumbnail Size:"), 0, row);
        thumbnailSizeSpinner = new Spinner<>(50, 400, 200, 25);
        thumbnailSizeSpinner.setEditable(true);
        thumbnailSizeSpinner.setPrefWidth(100);
        grid.add(thumbnailSizeSpinner, 1, row);
        grid.add(new Label("pixels"), 2, row++);

        // Results per page
        grid.add(new Label("Results Per Page:"), 0, row);
        resultsPerPageSpinner = new Spinner<>(10, 200, 50, 10);
        resultsPerPageSpinner.setEditable(true);
        resultsPerPageSpinner.setPrefWidth(100);
        grid.add(resultsPerPageSpinner, 1, row++);

        // Config file location
        Label configPathLabel = new Label("Config file: " + configService.getConfigPath());
        configPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        pane.getChildren().addAll(grid, new Separator(), configPathLabel);

        return pane;
    }

    private VBox createLoggingPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Enable logging
        grid.add(new Label("Enable Logging:"), 0, row);
        loggingEnabledCheckbox = new CheckBox("Write logs to file");
        grid.add(loggingEnabledCheckbox, 1, row++);

        // Log level
        grid.add(new Label("Log Level:"), 0, row);
        logLevelCombo = new ComboBox<>();
        logLevelCombo.getItems().addAll("DEBUG", "INFO", "WARN", "ERROR");
        logLevelCombo.setPrefWidth(120);
        grid.add(logLevelCombo, 1, row++);

        // Log level descriptions
        Label levelDescLabel = new Label(
                "DEBUG: All messages including detailed debug info\n" +
                "INFO: General information, warnings, and errors\n" +
                "WARN: Warnings and errors only\n" +
                "ERROR: Errors only"
        );
        levelDescLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Log file location
        String logPath = System.getProperty("user.home") + "/.photostat/photostat.log";
        Label logPathLabel = new Label("Log file: " + logPath);
        logPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Log file buttons
        Button openLogButton = new Button("Open Log File");
        openLogButton.setOnAction(e -> openLogFile(logPath));

        Button clearLogButton = new Button("Clear Log File");
        clearLogButton.setOnAction(e -> clearLogFile(logPath));

        HBox logButtonsBox = new HBox(10, openLogButton, clearLogButton);

        // Note about restart
        Label noteLabel = new Label("Note: Changes to logging settings require an application restart to take effect.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #996600;");

        pane.getChildren().addAll(grid, new Separator(), levelDescLabel, logPathLabel, logButtonsBox, new Separator(), noteLabel);

        return pane;
    }

    private void loadSettings() {
        hostField.setText(configService.getOpenSearchHost());
        portField.setText(String.valueOf(configService.getOpenSearchPort()));
        sslCheckbox.setSelected(configService.isOpenSearchSsl());
        usernameField.setText(configService.getOpenSearchUsername());
        passwordField.setText(configService.getOpenSearchPassword());
        indexNameField.setText(configService.getIndexName());
        exifToolPathField.setText(configService.getExifToolPath());
        batchSizeSpinner.getValueFactory().setValue(configService.getBatchSize());
        thumbnailSizeSpinner.getValueFactory().setValue(configService.getThumbnailSize());
        resultsPerPageSpinner.getValueFactory().setValue(configService.getResultsPerPage());

        // Logging settings
        loggingEnabledCheckbox.setSelected(configService.isLoggingEnabled());
        logLevelCombo.setValue(configService.getLoggingLevel());
    }

    private void saveSettings() {
        configService.setOpenSearchHost(hostField.getText().trim());

        try {
            configService.setOpenSearchPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            configService.setOpenSearchPort(9200);
        }

        configService.setOpenSearchSsl(sslCheckbox.isSelected());
        configService.setOpenSearchUsername(usernameField.getText().trim());
        configService.setOpenSearchPassword(passwordField.getText());
        configService.setIndexName(indexNameField.getText().trim());
        configService.setExifToolPath(exifToolPathField.getText().trim());
        configService.setBatchSize(batchSizeSpinner.getValue());
        configService.setThumbnailSize(thumbnailSizeSpinner.getValue());
        configService.setResultsPerPage(resultsPerPageSpinner.getValue());

        // Logging settings
        configService.setLoggingEnabled(loggingEnabledCheckbox.isSelected());
        configService.setLoggingLevel(logLevelCombo.getValue());

        configService.saveConfig();
    }

    private void testConnection() {
        connectionStatusLabel.setText("Testing...");
        connectionStatusLabel.setStyle("-fx-text-fill: #666;");

        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = 9200;
        }
        boolean ssl = sslCheckbox.isSelected();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        final int finalPort = port;

        Thread thread = new Thread(() -> {
            try {
                openSearchService.connect(host, finalPort, ssl, username, password);
                boolean success = openSearchService.testConnection();

                Platform.runLater(() -> {
                    if (success) {
                        connectionStatusLabel.setText("Connection successful!");
                        connectionStatusLabel.setStyle("-fx-text-fill: #00aa00;");
                    } else {
                        connectionStatusLabel.setText("Connection failed");
                        connectionStatusLabel.setStyle("-fx-text-fill: #cc0000;");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("Error: " + e.getMessage());
                    connectionStatusLabel.setStyle("-fx-text-fill: #cc0000;");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void browseForExifTool() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Select ExifTool Executable");

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("Executables", "*.exe")
            );
        }

        java.io.File selected = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (selected != null) {
            exifToolPathField.setText(selected.getAbsolutePath());
        }
    }

    private void openLogFile(String logPath) {
        try {
            java.io.File logFile = new java.io.File(logPath);
            if (!logFile.exists()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Log File");
                alert.setHeaderText(null);
                alert.setContentText("Log file does not exist yet. Enable logging and restart the application.");
                alert.show();
                return;
            }

            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(logFile);
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("notepad", logPath);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", logPath);
                } else {
                    pb = new ProcessBuilder("xdg-open", logPath);
                }
                pb.start();
            }
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Failed to open log file: " + e.getMessage());
            alert.show();
        }
    }

    private void clearLogFile(String logPath) {
        try {
            java.io.File logFile = new java.io.File(logPath);
            if (!logFile.exists()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Log File");
                alert.setHeaderText(null);
                alert.setContentText("Log file does not exist.");
                alert.show();
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Clear Log File");
            confirm.setHeaderText(null);
            confirm.setContentText("Are you sure you want to clear the log file? This cannot be undone.");
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    try {
                        new java.io.FileWriter(logFile, false).close();
                        Alert success = new Alert(Alert.AlertType.INFORMATION);
                        success.setTitle("Log File");
                        success.setHeaderText(null);
                        success.setContentText("Log file cleared successfully.");
                        success.show();
                    } catch (java.io.IOException e) {
                        Alert error = new Alert(Alert.AlertType.ERROR);
                        error.setTitle("Error");
                        error.setHeaderText(null);
                        error.setContentText("Failed to clear log file: " + e.getMessage());
                        error.show();
                    }
                }
            });
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Error: " + e.getMessage());
            alert.show();
        }
    }
}
