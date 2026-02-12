package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.ExifService;
import com.photostat.services.IndexerService;
import com.photostat.services.OpenSearchService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Panel for managing directories and indexing operations.
 */
public class IndexPanel extends BorderPane {

    private final ConfigService configService;
    private final IndexerService indexerService;
    private final ExifService exifService;
    private final OpenSearchService openSearchService;

    private ListView<String> directoryList;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label statsLabel;
    private Button startButton;
    private Button stopButton;
    private Button reindexButton;

    public IndexPanel() {
        this.configService = ConfigService.getInstance();
        this.indexerService = IndexerService.getInstance();
        this.exifService = ExifService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();

        initializeUI();
        setupCallbacks();
        loadDirectories();
        updateStats();
    }

    private void initializeUI() {
        setPadding(new Insets(20));

        // Left side - Directory management
        VBox directorySection = createDirectorySection();

        // Right side - Indexing controls
        VBox indexingSection = createIndexingSection();

        // Layout
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(directorySection, indexingSection);
        splitPane.setDividerPositions(0.5);

        setCenter(splitPane);
    }

    private VBox createDirectorySection() {
        VBox section = new VBox(15);
        section.setPadding(new Insets(10));

        // Title
        Label title = new Label("Directories to Index");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Directory list
        directoryList = new ListView<>();
        directoryList.setPrefHeight(300);
        VBox.setVgrow(directoryList, Priority.ALWAYS);

        // Quick access buttons for common locations
        Label quickAccessLabel = new Label("Quick Access:");
        HBox quickAccess = new HBox(10);
        quickAccess.setAlignment(Pos.CENTER_LEFT);

        // Home directory
        Button homeButton = new Button("Home");
        homeButton.setOnAction(e -> browseAndAdd(System.getProperty("user.home")));

        // Pictures directory
        Button picturesButton = new Button("Pictures");
        String pictures = System.getProperty("user.home") + File.separator + "Pictures";
        picturesButton.setOnAction(e -> browseAndAdd(pictures));

        // Detect drives (for Windows)
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            File[] roots = File.listRoots();
            for (File root : roots) {
                String drive = root.getPath();
                Button driveButton = new Button(drive);
                driveButton.setOnAction(event -> browseAndAdd(drive));
                quickAccess.getChildren().add(driveButton);
            }
        } else {
            // Linux/Mac common locations
            Button rootButton = new Button("/");
            rootButton.setOnAction(e -> browseAndAdd("/"));

            Button mediaButton = new Button("/media");
            mediaButton.setOnAction(e -> browseAndAdd("/media"));

            quickAccess.getChildren().addAll(homeButton, picturesButton, rootButton, mediaButton);
        }

        if (quickAccess.getChildren().isEmpty()) {
            quickAccess.getChildren().addAll(homeButton, picturesButton);
        }

        // Add/Remove buttons
        Button addButton = new Button("Add Directory...");
        addButton.setOnAction(e -> addDirectory());

        Button removeButton = new Button("Remove Selected");
        removeButton.setOnAction(e -> removeSelectedDirectory());
        removeButton.disableProperty().bind(
                directoryList.getSelectionModel().selectedItemProperty().isNull()
        );

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseDirectory());

        HBox buttonBox = new HBox(10, addButton, removeButton, browseButton);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        section.getChildren().addAll(
                title,
                directoryList,
                quickAccessLabel,
                quickAccess,
                new Separator(),
                buttonBox
        );

        return section;
    }

    private VBox createIndexingSection() {
        VBox section = new VBox(15);
        section.setPadding(new Insets(10));

        // Title
        Label title = new Label("Indexing");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Status
        statusLabel = new Label("Ready");
        statusLabel.setWrapText(true);

        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        // Stats
        statsLabel = new Label("Documents indexed: 0");
        statsLabel.setStyle("-fx-font-style: italic;");

        // Buttons
        startButton = new Button("Start Indexing");
        startButton.setOnAction(e -> startIndexing());
        startButton.setPrefWidth(150);

        stopButton = new Button("Stop");
        stopButton.setOnAction(e -> stopIndexing());
        stopButton.setDisable(true);
        stopButton.setPrefWidth(100);

        reindexButton = new Button("Re-index All");
        reindexButton.setOnAction(e -> reindexAll());
        reindexButton.setPrefWidth(150);

        HBox mainButtons = new HBox(10, startButton, stopButton);
        mainButtons.setAlignment(Pos.CENTER_LEFT);

        // Additional options
        Separator sep = new Separator();

        Label optionsLabel = new Label("Options:");

        Button deleteAllButton = new Button("Delete All Documents");
        deleteAllButton.getStyleClass().add("delete-button");
        deleteAllButton.setOnAction(e -> deleteAllDocuments());

        // ExifTool status
        Label exifToolLabel = new Label("ExifTool Status:");
        Label exifToolStatus = new Label(
                exifService.isExifToolAvailable() ? "Available" : "Not found (RAW support limited)"
        );
        exifToolStatus.getStyleClass().add(exifService.isExifToolAvailable() ? "text-success" : "text-warning");

        HBox exifToolBox = new HBox(10, exifToolLabel, exifToolStatus);

        // Supported formats info
        TitledPane formatsPane = new TitledPane();
        formatsPane.setText("Supported Formats");
        formatsPane.setExpanded(false);

        VBox formatsContent = new VBox(5);
        formatsContent.setPadding(new Insets(10));
        List<String> extensions = configService.getFileExtensions();
        Label formatsLabel = new Label(String.join(", ", extensions));
        formatsLabel.setWrapText(true);
        formatsContent.getChildren().add(formatsLabel);
        formatsPane.setContent(formatsContent);

        VBox.setVgrow(formatsPane, Priority.NEVER);

        section.getChildren().addAll(
                title,
                statusLabel,
                progressBar,
                statsLabel,
                mainButtons,
                reindexButton,
                sep,
                optionsLabel,
                deleteAllButton,
                new Separator(),
                exifToolBox,
                formatsPane
        );

        return section;
    }

    private void setupCallbacks() {
        indexerService.setCallbacks(
                // Status callback
                status -> Platform.runLater(() -> statusLabel.setText(status)),
                // Progress callback
                progress -> Platform.runLater(() -> progressBar.setProgress(progress)),
                // Completion callback
                stats -> Platform.runLater(() -> {
                    statusLabel.setText("Complete: " + stats);
                    startButton.setDisable(false);
                    stopButton.setDisable(true);
                    reindexButton.setDisable(false);
                    updateStats();
                })
        );
    }

    private void loadDirectories() {
        directoryList.getItems().clear();
        directoryList.getItems().addAll(configService.getDirectories());
    }

    private void addDirectory() {
        DirectoryBrowserDialog dialog = new DirectoryBrowserDialog(System.getProperty("user.home"));
        dialog.showAndWait().ifPresent(path -> {
            configService.addDirectory(path);
            configService.saveConfig();
            loadDirectories();
        });
    }

    private void browseDirectory() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Select Directory to Index");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File selected = chooser.showDialog(getScene().getWindow());
        if (selected != null) {
            configService.addDirectory(selected.getAbsolutePath());
            configService.saveConfig();
            loadDirectories();
        }
    }

    private void browseAndAdd(String initialPath) {
        Path path = Path.of(initialPath);
        if (!Files.exists(path)) {
            initialPath = System.getProperty("user.home");
        }

        DirectoryBrowserDialog dialog = new DirectoryBrowserDialog(initialPath);
        dialog.showAndWait().ifPresent(selectedPath -> {
            configService.addDirectory(selectedPath);
            configService.saveConfig();
            loadDirectories();
        });
    }

    private void removeSelectedDirectory() {
        String selected = directoryList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Directory");
            confirm.setHeaderText("Remove directory from index list?");
            confirm.setContentText("This will not delete any indexed documents. Directory: " + selected);

            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    configService.removeDirectory(selected);
                    configService.saveConfig();
                    loadDirectories();
                }
            });
        }
    }

    private void startIndexing() {
        if (directoryList.getItems().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Directories");
            alert.setHeaderText("No directories configured");
            alert.setContentText("Please add at least one directory before indexing.");
            alert.showAndWait();
            return;
        }

        startButton.setDisable(true);
        stopButton.setDisable(false);
        reindexButton.setDisable(true);
        progressBar.setProgress(0);
        statusLabel.setText("Indexing with " + configService.getIndexingThreads() + " threads...");

        indexerService.startIndexing();
    }

    private void stopIndexing() {
        indexerService.stopIndexing();
        startButton.setDisable(false);
        stopButton.setDisable(true);
        reindexButton.setDisable(false);
    }

    private void reindexAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Re-index All");
        confirm.setHeaderText("Re-index all files?");
        confirm.setContentText("This will re-process all files in the configured directories, " +
                "including files that have already been indexed.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                startButton.setDisable(true);
                stopButton.setDisable(false);
                reindexButton.setDisable(true);
                progressBar.setProgress(0);

                indexerService.reindexAll();
            }
        });
    }

    private void deleteAllDocuments() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete All Documents");
        confirm.setHeaderText("Delete all indexed documents?");
        confirm.setContentText("This will remove ALL documents from the OpenSearch index. " +
                "This action cannot be undone.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                // Delete all directories
                List<String> directories = configService.getDirectories();
                for (String dir : directories) {
                    try {
                        long deleted = indexerService.deleteDirectory(dir);
                        statusLabel.setText("Deleted " + deleted + " documents from " + dir);
                    } catch (Exception e) {
                        statusLabel.setText("Error: " + e.getMessage());
                    }
                }
                updateStats();
            }
        });
    }

    private void updateStats() {
        Thread thread = new Thread(() -> {
            try {
                if (!openSearchService.isConnected()) {
                    openSearchService.connect();
                }
                long count = openSearchService.getDocumentCount();
                Platform.runLater(() ->
                        statsLabel.setText(String.format("Documents indexed: %,d", count))
                );
            } catch (Exception e) {
                Platform.runLater(() ->
                        statsLabel.setText("Cannot connect to OpenSearch")
                );
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
}
