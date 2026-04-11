package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.ExifService;
import com.photostat.services.IndexerService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.SidecarService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Panel for managing directories and indexing operations.
 */
public class IndexPanel extends BorderPane {

    private final ConfigService configService;
    private final IndexerService indexerService;
    private final ExifService exifService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;

    private ListView<String> directoryList;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label statsLabel;
    private Button startButton;
    private Button stopButton;
    private Button reindexButton;
    private Button convertSidecarsButton;

    public IndexPanel() {
        this.configService = ConfigService.getInstance();
        this.indexerService = IndexerService.getInstance();
        this.exifService = ExifService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();

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

        convertSidecarsButton = new Button("Convert JSON sidecars to XMP…");
        convertSidecarsButton.setOnAction(e -> convertJsonSidecarsToXmp());
        convertSidecarsButton.setPrefWidth(260);

        HBox mainButtons = new HBox(10, startButton, stopButton);
        mainButtons.setAlignment(Pos.CENTER_LEFT);

        // Additional options
        Separator sep = new Separator();

        Label optionsLabel = new Label("Options:");

        Button deleteAllButton = new Button("Clear Index");
        deleteAllButton.getStyleClass().add("delete-button");
        deleteAllButton.setOnAction(e -> deleteAllDocuments());
        deleteAllButton.setTooltip(new Tooltip(
                "Removes all entries from the OpenSearch index for every\n" +
                "configured directory. Image files and sidecars\n" +
                "(.photostat.json / .xmp) are not touched.\n" +
                "You can re-populate the index at any time with \"Start Indexing\"."));

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
                convertSidecarsButton,
                sep,
                optionsLabel,
                deleteAllButton,
                new Separator(),
                exifToolBox,
                formatsPane
        );

        refreshSidecarConversionState();

        return section;
    }

    /**
     * Re-evaluate whether the "Convert JSON sidecars to XMP" button should be
     * enabled. Only meaningful when the user has chosen the XMP or Both sidecar
     * format in Settings.
     */
    public void refreshSidecarConversionState() {
        if (convertSidecarsButton == null) {
            return;
        }
        String format = configService.getSidecarFormat();
        boolean enabled = "xmp".equalsIgnoreCase(format) || "both".equalsIgnoreCase(format);
        convertSidecarsButton.setDisable(!enabled);
        if (enabled) {
            convertSidecarsButton.setTooltip(new Tooltip(
                    "Scan configured directories and rewrite every .photostat.json\n" +
                    "sidecar as a .xmp sidecar. Optionally deletes the JSON files."));
        } else {
            convertSidecarsButton.setTooltip(new Tooltip(
                    "Set Sidecar Format to XMP or Both in Settings → Indexing to enable."));
        }
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
                    refreshSidecarConversionState();
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
        List<String> directories = configService.getDirectories();
        if (directories.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Directories");
            alert.setHeaderText("No directories configured");
            alert.setContentText("Please add at least one directory before indexing.");
            alert.showAndWait();
            return;
        }

        // Build directory selection dialog
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Select Directories to Index");
        dialog.setHeaderText("Choose which directories to index:");

        ButtonType startButtonType = new ButtonType("Start Indexing", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(startButtonType, ButtonType.CANCEL);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Select All / Deselect All toggle
        CheckBox selectAllBox = new CheckBox("Select All");
        selectAllBox.setSelected(true);
        selectAllBox.setStyle("-fx-font-weight: bold;");
        content.getChildren().add(selectAllBox);
        content.getChildren().add(new Separator());

        // One checkbox per directory
        List<CheckBox> dirCheckBoxes = new ArrayList<>();
        for (String dir : directories) {
            CheckBox cb = new CheckBox(dir);
            cb.setSelected(true);
            dirCheckBoxes.add(cb);
            content.getChildren().add(cb);
        }

        // Disable OK button when nothing is checked
        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(startButtonType);
        Runnable updateOkButton = () -> {
            boolean anyChecked = dirCheckBoxes.stream().anyMatch(CheckBox::isSelected);
            okButton.setDisable(!anyChecked);
        };

        // Select All toggles all checkboxes
        selectAllBox.setOnAction(e -> {
            boolean selected = selectAllBox.isSelected();
            dirCheckBoxes.forEach(cb -> cb.setSelected(selected));
            updateOkButton.run();
        });

        // Individual checkboxes update Select All state and OK button
        for (CheckBox cb : dirCheckBoxes) {
            cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                boolean allSelected = dirCheckBoxes.stream().allMatch(CheckBox::isSelected);
                selectAllBox.setSelected(allSelected);
                updateOkButton.run();
            });
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(Math.min(directories.size() * 30 + 80, 400));
        dialog.getDialogPane().setContent(scrollPane);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == startButtonType) {
                List<String> selected = new ArrayList<>();
                for (CheckBox cb : dirCheckBoxes) {
                    if (cb.isSelected()) {
                        selected.add(cb.getText());
                    }
                }
                return selected;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(selectedDirs -> {
            startButton.setDisable(true);
            stopButton.setDisable(false);
            reindexButton.setDisable(true);
            convertSidecarsButton.setDisable(true);
            progressBar.setProgress(0);
            statusLabel.setText("Indexing " + selectedDirs.size() + " director" +
                    (selectedDirs.size() == 1 ? "y" : "ies") + " with " +
                    (Runtime.getRuntime().availableProcessors() * 2) + " threads...");

            indexerService.startIndexing(selectedDirs);
        });
    }

    private void stopIndexing() {
        indexerService.stopIndexing();
        startButton.setDisable(false);
        stopButton.setDisable(true);
        reindexButton.setDisable(false);
        refreshSidecarConversionState();
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
                convertSidecarsButton.setDisable(true);
                progressBar.setProgress(0);

                indexerService.reindexAll();
            }
        });
    }

    private void deleteAllDocuments() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear Index");
        confirm.setHeaderText("Clear the entire OpenSearch index?");
        confirm.setContentText("This removes every entry from the OpenSearch index for all " +
                "configured directories. Your image files and sidecar files (.photostat.json / " +
                ".xmp) are not affected, and you can re-populate the index at any time with " +
                "Start Indexing. This action cannot be undone.");

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

    /**
     * Scan configured directories for {@code .photostat.json} sidecars and
     * rewrite each one as an {@code .xmp} sidecar, optionally deleting the
     * JSON file on success. Runs in a background {@link Task} so the UI stays
     * responsive.
     */
    private void convertJsonSidecarsToXmp() {
        List<String> directories = configService.getDirectories();
        if (directories.isEmpty()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Convert JSON sidecars to XMP");
            info.setHeaderText("No directories configured");
            info.setContentText("Add at least one directory to the indexing list first.");
            info.showAndWait();
            return;
        }

        // Scan synchronously — cheap compared to the conversion, and we want
        // an accurate count for the confirmation dialog.
        List<Path> jsonSidecars = findJsonSidecars(directories);
        if (jsonSidecars.isEmpty()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Convert JSON sidecars to XMP");
            info.setHeaderText("No JSON sidecars found");
            info.setContentText("None of your configured directories contain .photostat.json files.");
            info.showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Convert JSON sidecars to XMP");
        confirm.setHeaderText("Convert " + jsonSidecars.size() + " sidecar file" +
                (jsonSidecars.size() == 1 ? "" : "s") + "?");
        confirm.setContentText("Each .photostat.json file will be rewritten as a .xmp sidecar " +
                "preserving all fields (rating, tags, persons, place, analysis cache, cloud uploads).");

        CheckBox deleteJsonCb = new CheckBox("Delete .photostat.json files after successful conversion");
        deleteJsonCb.setSelected(false);
        VBox extraContent = new VBox(10, new Label(confirm.getContentText()), deleteJsonCb);
        extraContent.setPadding(new Insets(5, 0, 0, 0));
        confirm.getDialogPane().setContent(extraContent);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                runConversionTask(jsonSidecars, deleteJsonCb.isSelected());
            }
        });
    }

    /**
     * Walk the configured directories and collect every {@code .photostat.json}
     * file. Runs on the calling thread; cheap for typical collection sizes.
     */
    private List<Path> findJsonSidecars(List<String> directories) {
        List<Path> found = new ArrayList<>();
        for (String dir : directories) {
            Path dirPath = Path.of(dir);
            if (!Files.isDirectory(dirPath)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dirPath)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".photostat.json"))
                        .forEach(found::add);
            } catch (Exception e) {
                statusLabel.setText("Failed to scan " + dir + ": " + e.getMessage());
            }
        }
        return found;
    }

    private void runConversionTask(List<Path> jsonSidecars, boolean deleteJsonAfter) {
        startButton.setDisable(true);
        reindexButton.setDisable(true);
        convertSidecarsButton.setDisable(true);
        progressBar.setProgress(0);

        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() {
                int converted = 0;
                int skippedEmpty = 0;
                int skippedNoJson = 0;
                int failed = 0;
                int total = jsonSidecars.size();

                for (int i = 0; i < total; i++) {
                    if (isCancelled()) break;
                    Path jsonPath = jsonSidecars.get(i);

                    // Derive image path by stripping ".photostat.json"
                    String fullName = jsonPath.toString();
                    String imagePath = fullName.substring(0,
                            fullName.length() - ".photostat.json".length());

                    updateMessage("Converting " + (i + 1) + " / " + total + ": " +
                            jsonPath.getFileName());
                    updateProgress(i, total);

                    SidecarService.ConversionResult result =
                            sidecarService.convertJsonToXmp(imagePath, deleteJsonAfter);
                    switch (result) {
                        case CONVERTED -> converted++;
                        case SKIPPED_EMPTY -> skippedEmpty++;
                        case SKIPPED_NO_JSON -> skippedNoJson++;
                        case FAILED -> failed++;
                    }
                }

                updateProgress(total, total);
                return new int[]{converted, skippedEmpty, skippedNoJson, failed};
            }
        };

        task.messageProperty().addListener((obs, oldMsg, newMsg) ->
                Platform.runLater(() -> statusLabel.setText(newMsg)));
        task.progressProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> progressBar.setProgress(newVal.doubleValue())));

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            int[] stats = task.getValue();
            String summary = String.format(
                    "Converted %d · Skipped %d empty · Skipped %d missing · Failed %d%s",
                    stats[0], stats[1], stats[2], stats[3],
                    deleteJsonAfter ? " (JSON files deleted)" : "");
            statusLabel.setText(summary);
            startButton.setDisable(false);
            reindexButton.setDisable(false);
            refreshSidecarConversionState();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable ex = task.getException();
            statusLabel.setText("Conversion failed: " +
                    (ex != null ? ex.getMessage() : "unknown error"));
            startButton.setDisable(false);
            reindexButton.setDisable(false);
            refreshSidecarConversionState();
        }));

        Thread thread = new Thread(task, "sidecar-conversion");
        thread.setDaemon(true);
        thread.start();
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
