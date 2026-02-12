package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.FileOperationsService;
import com.photostat.services.ImageAnalysisService;
import com.photostat.services.IndexerService;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.SidecarService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.DirectoryChooser;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Panel displaying search results in a table with thumbnails.
 */
public class ResultsPanel extends VBox {

    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;
    private final ConfigService configService;
    private final FileOperationsService fileOperationsService;
    private final ImageAnalysisService imageAnalysisService;
    private final SidecarService sidecarService;
    private final IndexerService indexerService;
    private final LoggingService logger;

    private TableView<ImageMetadata> resultsTable;
    private Label resultsCountLabel;
    private Pagination pagination;

    private String currentQuery = "";
    private Map<String, Object> currentFilters;
    private long totalResults = 0;

    private Consumer<ImageMetadata> selectionCallback;
    private Consumer<Map<String, Map<String, Long>>> aggregationsCallback;
    private Consumer<String> statusCallback;
    private Consumer<ImageMetadata> ratingChangedCallback;

    private Button analyzeSelectedBtn;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ResultsPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.configService = ConfigService.getInstance();
        this.fileOperationsService = FileOperationsService.getInstance();
        this.imageAnalysisService = ImageAnalysisService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.indexerService = IndexerService.getInstance();
        this.logger = LoggingService.getInstance();

        initializeUI();
    }

    private void initializeUI() {
        setSpacing(10);
        setPadding(new Insets(10));

        // Results count and info
        resultsCountLabel = new Label("No results");
        resultsCountLabel.setStyle("-fx-font-weight: bold;");

        // Create table
        resultsTable = new TableView<>();
        resultsTable.setPlaceholder(new Label("No images found. Try searching or indexing images."));
        resultsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        // Create columns
        createColumns();

        // Selection listener
        resultsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (selectionCallback != null && newVal != null) {
                selectionCallback.accept(newVal);
            }
        });

        // Toolbar for bulk operations
        analyzeSelectedBtn = new Button("Analyze Selected");
        analyzeSelectedBtn.setOnAction(e -> analyzeSelectedImages());
        analyzeSelectedBtn.setTooltip(new Tooltip("Analyze selected images with AI"));

        Button copySelectedBtn = new Button("Copy Selected...");
        copySelectedBtn.setOnAction(e -> copySelectedImages());

        Button moveSelectedBtn = new Button("Move Selected...");
        moveSelectedBtn.setOnAction(e -> moveSelectedImages());

        Button deleteSelectedBtn = new Button("Delete Selected");
        deleteSelectedBtn.getStyleClass().add("delete-button");
        deleteSelectedBtn.setOnAction(e -> deleteSelectedImages());

        Label selectionLabel = new Label("(Use Ctrl+Click or Shift+Click to select multiple)");
        selectionLabel.getStyleClass().add("info-label-small");

        Button slideshowBtn = new Button("Slideshow");
        slideshowBtn.setOnAction(e -> launchSlideshow());
        slideshowBtn.setTooltip(new Tooltip("Full-screen slideshow (F5)"));

        HBox toolbar = new HBox(10, slideshowBtn, analyzeSelectedBtn, copySelectedBtn, moveSelectedBtn, deleteSelectedBtn, selectionLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Double-click to open file
        resultsTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                ImageMetadata selected = resultsTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    openFile(selected.getFilePath());
                }
            }
        });

        // Keyboard shortcuts
        resultsTable.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F5) {
                launchSlideshow();
                event.consume();
                return;
            }
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown()) {
                return;
            }
            ImageMetadata selected = resultsTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            String rating = null;
            KeyCode code = event.getCode();
            if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) rating = "*";
            else if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) rating = "**";
            else if (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3) rating = "***";
            else if (code == KeyCode.DIGIT4 || code == KeyCode.NUMPAD4) rating = "****";
            else if (code == KeyCode.DIGIT5 || code == KeyCode.NUMPAD5) rating = "*****";
            else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) rating = "";
            else return;
            event.consume();
            applyRating(selected, rating.isEmpty() ? null : rating);
        });

        // Pagination
        int pageSize = configService.getResultsPerPage();
        pagination = new Pagination(1, 0);
        pagination.setMaxPageIndicatorCount(10);
        pagination.currentPageIndexProperty().addListener((obs, oldVal, newVal) -> {
            loadPage(newVal.intValue());
        });

        getChildren().addAll(resultsCountLabel, toolbar, resultsTable, pagination);
    }

    private void createColumns() {
        // Thumbnail column - size driven by configured thumbnail size
        int thumbSize = configService.getThumbnailSize();
        int fitSize = Math.max(60, thumbSize / 2);
        TableColumn<ImageMetadata, ImageView> thumbnailCol = new TableColumn<>("Thumbnail");
        thumbnailCol.setPrefWidth(fitSize + 20);
        thumbnailCol.setCellValueFactory(cellData -> {
            ImageMetadata metadata = cellData.getValue();
            ImageView imageView = new ImageView();
            imageView.setFitWidth(fitSize);
            imageView.setFitHeight(fitSize);
            imageView.setPreserveRatio(true);

            // Load thumbnail asynchronously
            thumbnailService.getThumbnailAsync(metadata.getFilePath(), (path, thumbnail) -> {
                Platform.runLater(() -> imageView.setImage(thumbnail));
            });

            return new SimpleObjectProperty<>(imageView);
        });

        // Filename column
        TableColumn<ImageMetadata, String> filenameCol = new TableColumn<>("Filename");
        filenameCol.setPrefWidth(200);
        filenameCol.setCellValueFactory(new PropertyValueFactory<>("fileName"));

        // Rating column
        TableColumn<ImageMetadata, String> ratingCol = new TableColumn<>("Rating");
        ratingCol.setPrefWidth(70);
        ratingCol.setCellValueFactory(cellData -> {
            ImageMetadata m = cellData.getValue();
            String rating = m.getRating();
            if (rating == null || rating.isEmpty()) {
                return new SimpleStringProperty("");
            }
            return new SimpleStringProperty(rating.replace('*', '\u2605'));
        });

        // Camera column
        TableColumn<ImageMetadata, String> cameraCol = new TableColumn<>("Camera");
        cameraCol.setPrefWidth(150);
        cameraCol.setCellValueFactory(cellData -> {
            ImageMetadata m = cellData.getValue();
            String camera = "";
            if (m.getCameraMake() != null) {
                camera = m.getCameraMake();
            }
            if (m.getCameraModel() != null) {
                if (!camera.isEmpty()) {
                    camera += " ";
                }
                camera += m.getCameraModel();
            }
            return new SimpleStringProperty(camera);
        });

        // Date column
        TableColumn<ImageMetadata, String> dateCol = new TableColumn<>("Date Taken");
        dateCol.setPrefWidth(130);
        dateCol.setCellValueFactory(cellData -> {
            ImageMetadata m = cellData.getValue();
            if (m.getDateTaken() != null) {
                return new SimpleStringProperty(m.getDateTaken().format(DATE_FORMAT));
            }
            return new SimpleStringProperty("");
        });

        // ISO column
        TableColumn<ImageMetadata, Integer> isoCol = new TableColumn<>("ISO");
        isoCol.setPrefWidth(60);
        isoCol.setCellValueFactory(new PropertyValueFactory<>("iso"));

        // Aperture column
        TableColumn<ImageMetadata, String> apertureCol = new TableColumn<>("Aperture");
        apertureCol.setPrefWidth(70);
        apertureCol.setCellValueFactory(cellData -> {
            ImageMetadata m = cellData.getValue();
            return new SimpleStringProperty(m.getApertureString());
        });

        // Shutter speed column
        TableColumn<ImageMetadata, String> shutterCol = new TableColumn<>("Shutter");
        shutterCol.setPrefWidth(80);
        shutterCol.setCellValueFactory(new PropertyValueFactory<>("shutterSpeed"));

        // Focal length column
        TableColumn<ImageMetadata, String> focalCol = new TableColumn<>("Focal");
        focalCol.setPrefWidth(70);
        focalCol.setCellValueFactory(cellData -> {
            ImageMetadata m = cellData.getValue();
            return new SimpleStringProperty(m.getFocalLengthString());
        });

        // File type column
        TableColumn<ImageMetadata, String> typeCol = new TableColumn<>("Type");
        typeCol.setPrefWidth(50);
        typeCol.setCellValueFactory(new PropertyValueFactory<>("fileType"));

        resultsTable.getColumns().addAll(
                thumbnailCol, filenameCol, ratingCol, cameraCol, dateCol,
                isoCol, apertureCol, shutterCol, focalCol, typeCol
        );
    }

    /**
     * Execute a search with the given query and filters.
     */
    public void search(String query, Map<String, Object> filters) {
        this.currentQuery = query != null ? query : "";
        this.currentFilters = filters;

        // Reset to first page
        pagination.setCurrentPageIndex(0);
        loadPage(0);
    }

    /**
     * Load a specific page of results.
     */
    private void loadPage(int page) {
        int pageSize = configService.getResultsPerPage();
        int from = page * pageSize;

        logger.info("ResultsPanel", "Loading page " + page + " (from=" + from + ", size=" + pageSize + ")");
        logger.debug("ResultsPanel", "Query: '" + currentQuery + "', Filters: " + currentFilters);

        Thread thread = new Thread(() -> {
            try {
                logger.debug("ResultsPanel", "Calling openSearchService.search...");
                OpenSearchService.SearchResult result = openSearchService.search(
                        currentQuery, currentFilters, from, pageSize);
                logger.debug("ResultsPanel", "Search completed, got " + result.getResults().size() + " results");

                Platform.runLater(() -> {
                    resultsTable.getItems().clear();
                    resultsTable.getItems().addAll(result.getResults());

                    totalResults = result.getTotal();
                    int totalPages = Math.max(1, (int) Math.ceil((double) totalResults / pageSize));

                    resultsCountLabel.setText(String.format("Found %,d images", totalResults));
                    pagination.setPageCount(totalPages);

                    logger.info("ResultsPanel", "Displayed " + result.getResults().size() + " of " + totalResults + " total results");

                    // Notify aggregations callback
                    if (aggregationsCallback != null) {
                        aggregationsCallback.accept(result.getAggregations());
                    }

                    // Select first item if available
                    if (!resultsTable.getItems().isEmpty()) {
                        resultsTable.getSelectionModel().selectFirst();
                    }
                });

            } catch (Exception e) {
                logger.error("ResultsPanel", "Search failed", e);
                Platform.runLater(() -> {
                    resultsCountLabel.setText("Search error: " + e.getMessage());
                    resultsTable.getItems().clear();
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Open a file with the system default application.
     */
    private void openFile(String filePath) {
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            desktop.open(new java.io.File(filePath));
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Cannot open file");
            alert.setContentText("Failed to open: " + filePath + "\n" + e.getMessage());
            alert.showAndWait();
        }
    }

    /**
     * Set callback for selection changes.
     */
    public void setSelectionCallback(Consumer<ImageMetadata> callback) {
        this.selectionCallback = callback;
    }

    /**
     * Set callback for aggregations (for facets).
     */
    public void setAggregationsCallback(Consumer<Map<String, Map<String, Long>>> callback) {
        this.aggregationsCallback = callback;
    }

    /**
     * Get the currently selected metadata.
     */
    public ImageMetadata getSelectedMetadata() {
        return resultsTable.getSelectionModel().getSelectedItem();
    }

    /**
     * Refresh the current page (reloads from OpenSearch).
     */
    public void refresh() {
        loadPage(pagination.getCurrentPageIndex());
    }

    /**
     * Refresh the table display without reloading data from OpenSearch.
     * Use this when the ImageMetadata objects have been updated in memory
     * (e.g., after saving custom metadata) and just need to redraw.
     */
    public void refreshTableDisplay() {
        resultsTable.refresh();
    }

    /**
     * Copy selected images to a chosen directory.
     */
    private void copySelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to copy.");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Destination Directory");
        File destination = chooser.showDialog(getScene().getWindow());

        if (destination != null) {
            List<String> paths = selected.stream()
                    .map(ImageMetadata::getFilePath)
                    .collect(Collectors.toList());

            FileOperationsService.BatchOperationResult result =
                    fileOperationsService.copyImages(paths, destination.toPath(), false);

            if (result.hasErrors()) {
                logger.warn("ResultsPanel", "Copy errors: " + String.join(", ", result.errors));
            }

            // Ask if user wants to index the copied files
            String indexMessage = "\nIndex not updated.";
            if (result.successCount > 0) {
                Alert indexConfirm = new Alert(Alert.AlertType.CONFIRMATION);
                indexConfirm.setTitle("Index Copied Files?");
                indexConfirm.setHeaderText("Add copied files to search index?");
                indexConfirm.setContentText("Do you want to index the copied files at the new location so they appear in search results?");

                var indexResponse = indexConfirm.showAndWait();
                if (indexResponse.isPresent() && indexResponse.get() == ButtonType.OK) {
                    int indexed = 0;
                    for (ImageMetadata metadata : selected) {
                        try {
                            Path oldPath = Path.of(metadata.getFilePath());
                            Path newPath = destination.toPath().resolve(oldPath.getFileName());
                            if (indexerService.indexSingleFile(newPath.toString())) {
                                indexed++;
                            }
                        } catch (Exception e) {
                            logger.error("ResultsPanel", "Failed to index copied file", e);
                        }
                    }
                    indexMessage = "\nIndexed " + indexed + " file(s) at new location.";
                    logger.info("ResultsPanel", "Indexed " + indexed + " copied files at new location");
                }
            }

            showAlert(Alert.AlertType.INFORMATION, "Copy Complete", result.getSummary() + indexMessage);
        }
    }

    /**
     * Move selected images to a chosen directory.
     */
    private void moveSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to move.");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Destination Directory");
        File destination = chooser.showDialog(getScene().getWindow());

        if (destination != null) {
            List<String> paths = selected.stream()
                    .map(ImageMetadata::getFilePath)
                    .collect(Collectors.toList());

            FileOperationsService.BatchOperationResult result =
                    fileOperationsService.moveImages(paths, destination.toPath(), false);

            if (result.hasErrors()) {
                logger.warn("ResultsPanel", "Move errors: " + String.join(", ", result.errors));
            }

            // Update index for moved files
            String indexMessage = "\nIndex not updated.";
            if (result.successCount > 0) {
                int reindexed = 0;
                for (ImageMetadata metadata : selected) {
                    try {
                        // Delete old entry from index
                        openSearchService.deleteDocument(metadata.getFilePath());

                        // Re-index at new location
                        Path oldPath = Path.of(metadata.getFilePath());
                        Path newPath = destination.toPath().resolve(oldPath.getFileName());
                        if (indexerService.indexSingleFile(newPath.toString())) {
                            reindexed++;
                        }
                    } catch (Exception e) {
                        logger.error("ResultsPanel", "Failed to update index for moved file", e);
                    }
                }
                indexMessage = "\nIndex updated: " + reindexed + " file(s) re-indexed at new location.";
                logger.info("ResultsPanel", "Re-indexed " + reindexed + " moved files at new location");
                refresh();
            }

            showAlert(Alert.AlertType.INFORMATION, "Move Complete", result.getSummary() + indexMessage);
        }
    }

    /**
     * Delete selected images after confirmation.
     */
    private void deleteSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to delete.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Delete");
        confirm.setHeaderText("Delete " + selected.size() + " image(s)?");
        confirm.setContentText("This will permanently delete the selected files from disk and remove them from the index. This cannot be undone.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                List<String> paths = selected.stream()
                        .map(ImageMetadata::getFilePath)
                        .collect(Collectors.toList());

                FileOperationsService.BatchOperationResult result =
                        fileOperationsService.deleteImages(paths, true);

                // Remove from index
                int indexRemoved = 0;
                for (ImageMetadata metadata : selected) {
                    try {
                        if (openSearchService.deleteDocument(metadata.getFilePath())) {
                            indexRemoved++;
                        }
                    } catch (Exception e) {
                        logger.error("ResultsPanel", "Failed to remove from index: " + metadata.getFilePath(), e);
                    }
                }

                String summary = result.getSummary() + "\nRemoved " + indexRemoved + " from index.";
                showAlert(Alert.AlertType.INFORMATION, "Delete Complete", summary);

                if (result.hasErrors()) {
                    logger.warn("ResultsPanel", "Delete errors: " + String.join(", ", result.errors));
                }

                // Refresh results
                if (result.successCount > 0) {
                    refresh();
                }
            }
        });
    }

    /**
     * Analyze selected images using Claude AI.
     */
    private void analyzeSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to analyze.");
            return;
        }

        if (!imageAnalysisService.isConfigured()) {
            showAlert(Alert.AlertType.ERROR, "API Key Required",
                    "Please configure your API key in Settings (AI Analysis tab).");
            return;
        }

        // Filter to only supported formats
        List<ImageMetadata> supportedImages = selected.stream()
                .filter(m -> {
                    String path = m.getFilePath().toLowerCase();
                    return path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                           path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".webp");
                })
                .collect(Collectors.toList());

        if (supportedImages.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Supported Images",
                    "None of the selected images are in a supported format (JPG, PNG, GIF, WebP).");
            return;
        }

        int unsupported = selected.size() - supportedImages.size();
        String unsupportedMsg = unsupported > 0 ?
                "\n(" + unsupported + " unsupported format(s) will be skipped)" : "";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        String providerName = imageAnalysisService.getProviderName();
        confirm.setTitle("Analyze Images");
        confirm.setHeaderText("Analyze " + supportedImages.size() + " image(s) with " + providerName + "?");
        confirm.setContentText("This will use the " + providerName + " API to analyze each image and populate metadata fields (tags, persons, place, rating)." +
                unsupportedMsg + "\n\nNote: API usage incurs costs.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                analyzeImagesInBackground(supportedImages);
            }
        });
    }

    /**
     * Analyze images in a background thread with progress dialog.
     */
    private void analyzeImagesInBackground(List<ImageMetadata> images) {
        analyzeSelectedBtn.setDisable(true);
        updateStatus("Analyzing " + images.size() + " image(s)...");

        // Create progress dialog
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initStyle(StageStyle.UTILITY);
        progressStage.setTitle("Analyzing Images");
        progressStage.setResizable(false);

        VBox progressContent = new VBox(15);
        progressContent.setPadding(new Insets(20));
        progressContent.setAlignment(Pos.CENTER);
        progressContent.setPrefWidth(450);

        String providerName = imageAnalysisService.getProviderName();
        Label titleLabel = new Label("Analyzing images with " + providerName + "...");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setPrefHeight(25);

        Label progressLabel = new Label("0 of " + images.size());
        progressLabel.setStyle("-fx-font-size: 12px;");

        Label currentFileLabel = new Label("Preparing...");
        currentFileLabel.getStyleClass().add("info-label-small");
        currentFileLabel.setWrapText(true);
        currentFileLabel.setMaxWidth(400);

        Label statusLabel = new Label("");
        statusLabel.getStyleClass().add("info-label-small");

        Button cancelButton = new Button("Cancel");
        final boolean[] cancelled = {false};
        cancelButton.setOnAction(e -> {
            cancelled[0] = true;
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling...");
            currentFileLabel.setText("Cancelling after current image...");
        });

        progressContent.getChildren().addAll(titleLabel, progressBar, progressLabel, currentFileLabel, statusLabel, cancelButton);

        javafx.scene.Scene progressScene = new javafx.scene.Scene(progressContent);
        progressStage.setScene(progressScene);
        progressStage.show();

        // Center on parent window
        if (getScene() != null && getScene().getWindow() != null) {
            progressStage.setX(getScene().getWindow().getX() + (getScene().getWindow().getWidth() - 450) / 2);
            progressStage.setY(getScene().getWindow().getY() + (getScene().getWindow().getHeight() - 200) / 2);
        }

        new Thread(() -> {
            int successCount = 0;
            int errorCount = 0;
            int skippedCount = 0;  // Cached/already analyzed
            List<String> errors = new ArrayList<>();
            final int totalImages = images.size();

            for (int i = 0; i < images.size(); i++) {
                if (cancelled[0]) {
                    break;
                }

                ImageMetadata metadata = images.get(i);
                final int current = i + 1;
                final double progress = (double) i / totalImages;

                // Check if analysis is cached (image unchanged, same model, same prompt)
                if (imageAnalysisService.isAnalysisCached(metadata.getFilePath())) {
                    skippedCount++;
                    final int skipCount = skippedCount;
                    Platform.runLater(() -> {
                        progressBar.setProgress(progress);
                        progressLabel.setText(current + " of " + totalImages);
                        currentFileLabel.setText("Cached: " + metadata.getFileName());
                        statusLabel.setText("Skipped (cached): " + skipCount);
                    });
                    logger.debug("ResultsPanel", "Skipping cached analysis for: " + metadata.getFilePath());
                    continue;
                }

                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    progressLabel.setText(current + " of " + totalImages);
                    currentFileLabel.setText("Analyzing: " + metadata.getFileName());
                    updateStatus("Analyzing image " + current + " of " + totalImages + ": " + metadata.getFileName());
                });

                try {
                    ImageAnalysisService.AnalysisResult result =
                            imageAnalysisService.analyzeImage(metadata.getFilePath());

                    if (result.hasError()) {
                        errorCount++;
                        errors.add(metadata.getFileName() + ": " + result.getError());
                        logger.warn("ResultsPanel", "Analysis failed for " + metadata.getFilePath() + ": " + result.getError());
                        final int errCount = errorCount;
                        Platform.runLater(() -> statusLabel.setText("Errors: " + errCount));
                    } else {
                        // Update metadata with analysis results
                        if (result.getTags() != null && !result.getTags().isEmpty()) {
                            metadata.setTags(result.getTags());
                        }
                        if (result.getPersons() != null && !result.getPersons().isEmpty()) {
                            metadata.setPersons(result.getPersons());
                        }
                        if (result.getPlace() != null && !result.getPlace().isEmpty()) {
                            metadata.setPlace(result.getPlace());
                        }
                        if (result.getRating() != null && !result.getRating().isEmpty()) {
                            metadata.setRating(result.getRating());
                        }

                        // Save to OpenSearch
                        openSearchService.updateDocument(metadata);

                        // Save to sidecar file
                        sidecarService.writeSidecar(metadata);

                        successCount++;
                        logger.info("ResultsPanel", "Analyzed and saved: " + metadata.getFilePath());
                    }
                } catch (Exception e) {
                    errorCount++;
                    errors.add(metadata.getFileName() + ": " + e.getMessage());
                    logger.error("ResultsPanel", "Analysis failed for " + metadata.getFilePath(), e);
                    final int errCount = errorCount;
                    Platform.runLater(() -> statusLabel.setText("Errors: " + errCount));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalErrorCount = errorCount;
            final int finalSkippedCount = skippedCount;
            final List<String> finalErrors = errors;
            final boolean wasCancelled = cancelled[0];

            Platform.runLater(() -> {
                progressStage.close();
                analyzeSelectedBtn.setDisable(false);

                String summary;
                if (wasCancelled) {
                    summary = "Analysis cancelled.\n" +
                            "Analyzed: " + finalSuccessCount + "\n" +
                            "Cached (skipped): " + finalSkippedCount + "\n" +
                            "Failed: " + finalErrorCount + "\n" +
                            "Remaining: " + (totalImages - finalSuccessCount - finalSkippedCount - finalErrorCount);
                } else {
                    summary = "Analysis complete.\n" +
                            "Analyzed: " + finalSuccessCount + "\n" +
                            "Cached (skipped): " + finalSkippedCount + "\n" +
                            "Failed: " + finalErrorCount;
                }

                if (!finalErrors.isEmpty()) {
                    summary += "\n\nErrors:\n" + String.join("\n", finalErrors.subList(0, Math.min(5, finalErrors.size())));
                    if (finalErrors.size() > 5) {
                        summary += "\n... and " + (finalErrors.size() - 5) + " more";
                    }
                }

                updateStatus("Analysis " + (wasCancelled ? "cancelled" : "complete") + ": " + finalSuccessCount + " analyzed, " + finalSkippedCount + " cached, " + finalErrorCount + " failed");
                showAlert(Alert.AlertType.INFORMATION, wasCancelled ? "Analysis Cancelled" : "Analysis Complete", summary);

                // Refresh table display to show updated metadata
                // (just redraws - data is already updated in memory)
                if (finalSuccessCount > 0) {
                    refreshTableDisplay();

                    // Re-trigger selection callback to refresh the detail panel
                    ImageMetadata selected = resultsTable.getSelectionModel().getSelectedItem();
                    if (selected != null && selectionCallback != null) {
                        selectionCallback.accept(selected);
                    }
                }
            });
        }).start();
    }

    /**
     * Set callback for status updates.
     */
    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    /**
     * Set callback for when rating changes via keyboard shortcut.
     */
    public void setRatingChangedCallback(Consumer<ImageMetadata> callback) {
        this.ratingChangedCallback = callback;
    }

    /**
     * Launch full-screen slideshow from current results.
     */
    private void launchSlideshow() {
        List<ImageMetadata> items = new ArrayList<>(resultsTable.getItems());
        if (items.isEmpty()) {
            return;
        }
        int selectedIndex = resultsTable.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }

        BiConsumer<ImageMetadata, String> callback = (metadata, rating) -> {
            Platform.runLater(() -> {
                resultsTable.refresh();
                if (ratingChangedCallback != null) {
                    ratingChangedCallback.accept(metadata);
                }
            });
        };

        SlideshowStage slideshow = new SlideshowStage(items, selectedIndex, callback);
        slideshow.show();
    }

    /**
     * Apply a rating to the given image and save in background.
     */
    private void applyRating(ImageMetadata metadata, String rating) {
        metadata.setRating(rating);
        resultsTable.refresh();

        String stars = rating != null ? rating.replace('*', '\u2605') : "";
        String statusMsg = rating != null
                ? "Rated " + stars + " \u2014 " + metadata.getFileName()
                : "Rating cleared \u2014 " + metadata.getFileName();
        updateStatus(statusMsg);

        if (ratingChangedCallback != null) {
            ratingChangedCallback.accept(metadata);
        }

        Thread thread = new Thread(() -> {
            try {
                openSearchService.updateDocument(metadata);
                sidecarService.writeSidecar(metadata);
            } catch (Exception e) {
                logger.error("ResultsPanel", "Failed to save rating for " + metadata.getFilePath(), e);
                Platform.runLater(() -> updateStatus("Failed to save rating: " + e.getMessage()));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Update the status bar.
     */
    private void updateStatus(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }

    /**
     * Show an alert dialog.
     */
    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
