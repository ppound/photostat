package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.FileOperationsService;
import com.photostat.services.ImageAnalysisService;
import com.photostat.services.IndexerService;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.LumaService;
import com.photostat.services.RcloneService;
import com.photostat.services.SidecarService;
import com.photostat.services.ThumbnailService;
import org.opensearch.client.opensearch._types.SortOrder;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final RcloneService rcloneService;
    private final SidecarService sidecarService;
    private final IndexerService indexerService;
    private final LoggingService logger;

    private TableView<ImageMetadata> resultsTable;
    private Label resultsCountLabel;
    private Pagination pagination;

    private String currentQuery = "";
    private Map<String, Object> currentFilters;
    // Result ordering. Null sort field = default (date taken, newest first).
    private String currentSortField = null;
    private SortOrder currentSortOrder = null;
    private long totalResults = 0;

    private Consumer<ImageMetadata> selectionCallback;
    private Consumer<Map<String, Map<String, Long>>> aggregationsCallback;
    private Consumer<String> statusCallback;
    private Consumer<ImageMetadata> ratingChangedCallback;
    private BiConsumer<String, String> chipClickCallback;

    private Button analyzeSelectedBtn;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ResultsPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.configService = ConfigService.getInstance();
        this.fileOperationsService = FileOperationsService.getInstance();
        this.imageAnalysisService = ImageAnalysisService.getInstance();
        this.rcloneService = RcloneService.getInstance();
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

        String multiSelectHint = "\n\nCtrl+Click to select multiple, Shift+Click to select a range.";

        // Toolbar for bulk operations
        analyzeSelectedBtn = new Button("Analyze Selected");
        analyzeSelectedBtn.setOnAction(e -> analyzeSelectedImages());
        analyzeSelectedBtn.setTooltip(new Tooltip("Analyze selected images with AI to populate tags, persons, place, and rating." + multiSelectHint));

        Button copySelectedBtn = new Button("Copy Selected...");
        copySelectedBtn.setOnAction(e -> copySelectedImages());
        copySelectedBtn.setTooltip(new Tooltip("Copy selected images to another directory." + multiSelectHint));

        Button moveSelectedBtn = new Button("Move...");
        moveSelectedBtn.setOnAction(e -> moveSelectedImages());
        moveSelectedBtn.setTooltip(new Tooltip("Move images to another directory and update the index. Operates on the current selection or full result set." + multiSelectHint));

        Button renameBtn = new Button("Rename...");
        renameBtn.setOnAction(e -> batchRenameImages());
        renameBtn.setTooltip(new Tooltip("Find/replace in filenames across the current selection or full result set." + multiSelectHint));

        Button deleteSelectedBtn = new Button("Delete...");
        deleteSelectedBtn.getStyleClass().add("delete-button");
        deleteSelectedBtn.setOnAction(e -> deleteSelectedImages());
        deleteSelectedBtn.setTooltip(new Tooltip("Permanently delete images from disk and remove from index. Operates on the current selection or full result set." + multiSelectHint));

        Button uploadSelectedBtn = new Button("Upload Selected...");
        uploadSelectedBtn.setOnAction(e -> uploadSelectedImages());
        uploadSelectedBtn.setTooltip(new Tooltip("Upload selected images to a cloud remote via rclone. Already-uploaded files can be skipped." + multiSelectHint));

        Button generateImageBtn = new Button("Generate Image");
        generateImageBtn.setOnAction(e -> generateFromSelectedImages());
        generateImageBtn.setTooltip(new Tooltip("Generate a new image with Luma AI using selected images as reference." + multiSelectHint));

        Button slideshowBtn = new Button("Slideshow");
        slideshowBtn.setOnAction(e -> launchSlideshow());
        slideshowBtn.setTooltip(new Tooltip("Full-screen slideshow starting from the selected image (F5).\nUse arrow keys to navigate, 1-5 to rate, 0 to clear rating."));

        // Sort control. Default order is date taken (newest first); "Aesthetic
        // (best first)" sorts by the AI aesthetic_score descending.
        Label sortLabel = new Label("Sort:");
        ComboBox<String> sortByCombo = new ComboBox<>();
        sortByCombo.getItems().addAll("Date (newest)", "Aesthetic (best first)");
        sortByCombo.setValue("Date (newest)");
        sortByCombo.setTooltip(new Tooltip("Order results. Aesthetic uses the AI quality score (0-100)."));
        sortByCombo.setOnAction(e -> {
            if ("Aesthetic (best first)".equals(sortByCombo.getValue())) {
                currentSortField = "aesthetic_score";
                currentSortOrder = SortOrder.Desc;
            } else {
                currentSortField = null;
                currentSortOrder = null;
            }
            // Re-run the current search from page 1 with the new ordering.
            pagination.setCurrentPageIndex(0);
            loadPage(0);
        });

        HBox toolbar = new HBox(10, slideshowBtn, analyzeSelectedBtn, generateImageBtn, copySelectedBtn, moveSelectedBtn, renameBtn, uploadSelectedBtn, deleteSelectedBtn, sortLabel, sortByCombo);
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

        // Context menu
        ContextMenu contextMenu = new ContextMenu();
        MenuItem analyzeMenuItem = new MenuItem("Analyze Selected");
        analyzeMenuItem.setOnAction(e -> analyzeSelectedImages());
        MenuItem generateMenuItem = new MenuItem("Generate Image with Luma");
        generateMenuItem.setOnAction(e -> generateFromSelectedImages());
        MenuItem copyMenuItem = new MenuItem("Copy Selected...");
        copyMenuItem.setOnAction(e -> copySelectedImages());
        MenuItem moveMenuItem = new MenuItem("Move...");
        moveMenuItem.setOnAction(e -> moveSelectedImages());
        MenuItem uploadMenuItem = new MenuItem("Upload Selected...");
        uploadMenuItem.setOnAction(e -> uploadSelectedImages());
        MenuItem reindexMenuItem = new MenuItem("Re-index Selected");
        reindexMenuItem.setOnAction(e -> reindexSelectedImages());
        MenuItem deleteMenuItem = new MenuItem("Delete...");
        deleteMenuItem.setOnAction(e -> deleteSelectedImages());
        contextMenu.getItems().addAll(analyzeMenuItem, generateMenuItem, new SeparatorMenuItem(),
                copyMenuItem, moveMenuItem, uploadMenuItem, reindexMenuItem, new SeparatorMenuItem(), deleteMenuItem);
        resultsTable.setContextMenu(contextMenu);

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
        TableColumn<ImageMetadata, String> thumbnailCol = new TableColumn<>("Thumbnail");
        thumbnailCol.setPrefWidth(fitSize + 20);
        // Use file path as the cell value so updateItem() fires only when the row changes
        thumbnailCol.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getFilePath()));
        thumbnailCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private String lastLoadedPath = null;

            {
                imageView.setFitWidth(fitSize);
                imageView.setFitHeight(fitSize);
                imageView.setPreserveRatio(true);
            }

            @Override
            protected void updateItem(String filePath, boolean empty) {
                super.updateItem(filePath, empty);
                if (empty || filePath == null) {
                    setGraphic(null);
                    lastLoadedPath = null;
                    return;
                }
                setGraphic(imageView);
                // Only fire async load if this cell is now showing a different image
                if (!filePath.equals(lastLoadedPath)) {
                    lastLoadedPath = filePath;
                    imageView.setImage(null);
                    thumbnailService.getThumbnailAsync(filePath, (path, thumbnail) ->
                            Platform.runLater(() -> {
                                // Guard against cell having been recycled to a different row
                                if (filePath.equals(lastLoadedPath)) {
                                    imageView.setImage(thumbnail);
                                }
                            }));
                }
            }
        });

        // Filename column with Labels popup
        TableColumn<ImageMetadata, ImageMetadata> filenameCol = new TableColumn<>("Filename");
        filenameCol.setPrefWidth(200);
        filenameCol.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue()));
        filenameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ImageMetadata item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }

                Label nameLabel = new Label(item.getFileName());
                nameLabel.setStyle("-fx-font-size: 13px;");

                List<String> persons = item.getPersons();
                String place = item.getPlace();
                List<String> tags = item.getTags();
                boolean hasPersons = persons != null && !persons.isEmpty();
                boolean hasPlace = place != null && !place.isEmpty();
                boolean hasTags = tags != null && !tags.isEmpty();

                if (!hasPersons && !hasPlace && !hasTags) {
                    VBox nameBox = new VBox(nameLabel);
                    nameBox.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(nameBox);
                    setText(null);
                    return;
                }

                Hyperlink labelsLink = new Hyperlink("Labels");
                labelsLink.getStyleClass().add("labels-link");
                labelsLink.setOnAction(e -> {
                    Popup popup = new Popup();
                    popup.setAutoHide(true);

                    FlowPane chipPane = new FlowPane();
                    chipPane.setHgap(4);
                    chipPane.setVgap(4);
                    chipPane.setPadding(new Insets(8));
                    chipPane.getStyleClass().add("labels-popup");
                    chipPane.setMaxWidth(300);

                    if (hasPersons) {
                        for (String person : persons) {
                            chipPane.getChildren().add(createChip(person, "persons", "result-chip-person", popup));
                        }
                    }
                    if (hasPlace) {
                        chipPane.getChildren().add(createChip(place, "place", "result-chip-place", popup));
                    }
                    if (hasTags) {
                        for (String tag : tags) {
                            chipPane.getChildren().add(createChip(tag, "tags", "result-chip-tag", popup));
                        }
                    }

                    popup.getContent().add(chipPane);
                    var bounds = labelsLink.localToScreen(labelsLink.getBoundsInLocal());
                    if (bounds != null) {
                        popup.show(labelsLink, bounds.getMinX(), bounds.getMaxY() + 2);
                    }
                });

                VBox cellBox = new VBox(2, nameLabel, labelsLink);
                cellBox.setAlignment(Pos.CENTER_LEFT);
                setGraphic(cellBox);
                setText(null);
            }

            private Label createChip(String text, String field, String styleClass, Popup popup) {
                Label chip = new Label(text);
                chip.getStyleClass().addAll("result-chip", styleClass);
                chip.setOnMouseClicked(event -> {
                    event.consume();
                    popup.hide();
                    if (chipClickCallback != null) {
                        chipClickCallback.accept(field, text);
                    }
                });
                return chip;
            }
        });

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

        // Aesthetic score column (stored 0..1, shown as 0-100 integer)
        TableColumn<ImageMetadata, String> scoreCol = new TableColumn<>("Score");
        scoreCol.setPrefWidth(60);
        scoreCol.setCellValueFactory(cellData -> {
            Double score = cellData.getValue().getAestheticScore();
            if (score == null) {
                return new SimpleStringProperty("");
            }
            return new SimpleStringProperty(String.valueOf((int) Math.round(score * 100)));
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
                thumbnailCol, filenameCol, ratingCol, scoreCol, cameraCol, dateCol,
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
                        currentQuery, currentFilters, from, pageSize, currentSortField, currentSortOrder);
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
     * Re-index the selected images: re-extract EXIF/metadata from disk and
     * overwrite the existing index documents. Useful when an image was indexed
     * by an older build (or a transient extraction failure) and is missing
     * metadata that is present in the file.
     */
    private void reindexSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to re-index.");
            return;
        }

        // Progress dialog (mirrors the analyze flow).
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initStyle(StageStyle.UTILITY);
        progressStage.setTitle("Re-indexing");
        progressStage.setResizable(false);

        VBox progressContent = new VBox(15);
        progressContent.setPadding(new Insets(20));
        progressContent.setAlignment(Pos.CENTER);
        progressContent.setPrefWidth(450);

        Label titleLabel = new Label("Re-indexing selected images...");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        final int total = selected.size();
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setPrefHeight(25);

        Label progressLabel = new Label("0 of " + total);
        Label currentFileLabel = new Label("Preparing...");
        currentFileLabel.getStyleClass().add("info-label-small");
        currentFileLabel.setWrapText(true);
        currentFileLabel.setMaxWidth(400);

        Button cancelButton = new Button("Cancel");
        final boolean[] cancelled = {false};
        cancelButton.setOnAction(e -> {
            cancelled[0] = true;
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling...");
        });

        progressContent.getChildren().addAll(titleLabel, progressBar, progressLabel, currentFileLabel, cancelButton);
        progressStage.setScene(new javafx.scene.Scene(progressContent));
        progressStage.show();
        if (getScene() != null && getScene().getWindow() != null) {
            progressStage.setX(getScene().getWindow().getX() + (getScene().getWindow().getWidth() - 450) / 2);
            progressStage.setY(getScene().getWindow().getY() + (getScene().getWindow().getHeight() - 200) / 2);
        }

        new Thread(() -> {
            int success = 0;
            int failed = 0;
            for (int i = 0; i < selected.size(); i++) {
                if (cancelled[0]) break;
                ImageMetadata metadata = selected.get(i);
                final int current = i + 1;
                final double progress = (double) i / total;
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    progressLabel.setText(current + " of " + total);
                    currentFileLabel.setText("Re-indexing: " + metadata.getFileName());
                });
                try {
                    if (indexerService.indexSingleFile(metadata.getFilePath())) {
                        success++;
                    } else {
                        failed++;
                        logger.warn("ResultsPanel", "Re-index failed for " + metadata.getFilePath());
                    }
                } catch (Exception ex) {
                    failed++;
                    logger.error("ResultsPanel", "Re-index failed for " + metadata.getFilePath(), ex);
                }
            }

            final int finalSuccess = success;
            final int finalFailed = failed;
            final boolean wasCancelled = cancelled[0];
            Platform.runLater(() -> {
                progressStage.close();
                String summary = (wasCancelled ? "Re-indexing cancelled.\n" : "Re-indexing complete.\n")
                        + "Re-indexed: " + finalSuccess + "\n"
                        + "Failed: " + finalFailed;
                showAlert(Alert.AlertType.INFORMATION, "Re-index Complete", summary);
                if (finalSuccess > 0) {
                    refresh();
                }
            });
        }, "reindex-selected").start();
    }

    /**
     * Which set a batch operation should run on.
     */
    private enum BatchSource { SELECTED, RESULTS }

    /**
     * Ask the user whether a batch operation should target the current
     * selection or the full current result set. Returns null if the user
     * cancels. If only one of the two is non-empty, the dialog is skipped.
     */
    private BatchSource askBatchSource(int selectedCount, long resultsCount, String title, String header) {
        if (resultsCount <= 0) return BatchSource.SELECTED;
        if (selectedCount == 0) return BatchSource.RESULTS;

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(null);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        ButtonType selectedBtn = new ButtonType("Selected (" + selectedCount + ")", ButtonBar.ButtonData.OK_DONE);
        ButtonType resultsBtn = new ButtonType("Current results (" + resultsCount + ")", ButtonBar.ButtonData.OTHER);
        dialog.getButtonTypes().setAll(selectedBtn, resultsBtn, ButtonType.CANCEL);

        Optional<ButtonType> chosen = dialog.showAndWait();
        if (chosen.isEmpty()) return null;
        ButtonType bt = chosen.get();
        if (bt == selectedBtn) return BatchSource.SELECTED;
        if (bt == resultsBtn) return BatchSource.RESULTS;
        return null;
    }

    /**
     * Fetch the full current result set on a background thread, then invoke
     * the given callback on the JavaFX thread. Matches the pattern used by
     * batch rename so conflict-/scope-detection sees the whole set, not just
     * the displayed page.
     */
    private void fetchCurrentResults(String operation, Consumer<List<ImageMetadata>> onReady) {
        Thread fetchThread = new Thread(() -> {
            List<ImageMetadata> results;
            try {
                int fetchSize = (int) Math.min(totalResults, 10000L);
                if (fetchSize <= 0) {
                    results = new ArrayList<>();
                } else {
                    OpenSearchService.SearchResult full = openSearchService.search(
                            currentQuery, currentFilters, 0, fetchSize);
                    results = full.getResults();
                }
            } catch (Exception ex) {
                logger.error("ResultsPanel", "Failed to fetch current results for " + operation, ex);
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, operation,
                        "Failed to fetch current results: " + ex.getMessage()));
                return;
            }
            final List<ImageMetadata> finalResults = results;
            Platform.runLater(() -> onReady.accept(finalResults));
        });
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    /**
     * Move images to a chosen directory. Operates on either the current
     * selection or the full current result set.
     */
    private void moveSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());

        if (selected.isEmpty() && totalResults == 0) {
            showAlert(Alert.AlertType.WARNING, "No Images", "Select images or run a search first.");
            return;
        }

        BatchSource source = askBatchSource(selected.size(), totalResults,
                "Move Images", "Which images do you want to move?");
        if (source == null) return;

        if (source == BatchSource.SELECTED) {
            performMove(selected);
        } else {
            fetchCurrentResults("Move", this::performMove);
        }
    }

    private void performMove(List<ImageMetadata> targets) {
        if (targets.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Images", "There are no images to move.");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Destination Directory");
        File destination = chooser.showDialog(getScene().getWindow());
        if (destination == null) return;

        List<String> paths = targets.stream()
                .map(ImageMetadata::getFilePath)
                .collect(Collectors.toList());

        FileOperationsService.BatchOperationResult result =
                fileOperationsService.moveImages(paths, destination.toPath(), false);

        if (result.hasErrors()) {
            logger.warn("ResultsPanel", "Move errors: " + String.join(", ", result.errors));
        }

        String indexMessage = "\nIndex not updated.";
        if (result.successCount > 0) {
            int reindexed = 0;
            for (ImageMetadata metadata : targets) {
                try {
                    openSearchService.deleteDocument(metadata.getFilePath());

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

    /**
     * Open the batch rename dialog. Pre-fetches the full current result set so
     * the dialog can offer "Current results" as a source in addition to the
     * user's selection.
     */
    private void batchRenameImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());

        if (selected.isEmpty() && totalResults == 0) {
            showAlert(Alert.AlertType.WARNING, "No Images", "Select images or run a search first.");
            return;
        }

        // Fetch the current result set (up to OpenSearch's default 10K cap) on
        // a background thread so the dialog can detect conflicts across the
        // whole set, not just the displayed page.
        Thread fetchThread = new Thread(() -> {
            List<ImageMetadata> currentResults;
            try {
                int fetchSize = (int) Math.min(totalResults, 10000L);
                if (fetchSize <= 0) {
                    currentResults = new ArrayList<>();
                } else {
                    OpenSearchService.SearchResult full = openSearchService.search(
                            currentQuery, currentFilters, 0, fetchSize);
                    currentResults = full.getResults();
                }
            } catch (Exception ex) {
                logger.error("ResultsPanel", "Failed to fetch current results for rename", ex);
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Rename",
                        "Failed to fetch current results: " + ex.getMessage()));
                return;
            }
            final List<ImageMetadata> finalResults = currentResults;
            Platform.runLater(() -> openRenameDialog(selected, finalResults));
        });
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    private void openRenameDialog(List<ImageMetadata> selected, List<ImageMetadata> currentResults) {
        BatchRenameDialog dialog = new BatchRenameDialog(selected, currentResults);
        dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().ifPresent(result -> applyRenames(result.getRenames()));
    }

    private void applyRenames(Map<String, String> renames) {
        if (renames == null || renames.isEmpty()) {
            return;
        }

        // Progress dialog so the user gets feedback during a multi-second
        // rename + reindex pass.
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initStyle(StageStyle.UTILITY);
        progressStage.setTitle("Renaming Files");
        progressStage.setResizable(false);
        if (getScene() != null && getScene().getWindow() != null) {
            progressStage.initOwner(getScene().getWindow());
        }

        Label titleLabel = new Label("Renaming files...");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setPrefHeight(20);
        Label progressLabel = new Label("0 of " + renames.size());
        Label currentFileLabel = new Label("Starting...");
        currentFileLabel.setWrapText(true);
        currentFileLabel.setMaxWidth(400);

        VBox progressContent = new VBox(12, titleLabel, progressBar, progressLabel, currentFileLabel);
        progressContent.setPadding(new Insets(20));
        progressContent.setAlignment(Pos.CENTER);
        progressContent.setPrefWidth(450);
        progressStage.setScene(new javafx.scene.Scene(progressContent));
        progressStage.show();

        Thread applyThread = new Thread(() -> {
            int success = 0;
            int failure = 0;
            int reindexed = 0;
            int analysisCachePreserved = 0;
            List<String> errors = new ArrayList<>();
            int total = renames.size();
            int i = 0;

            for (Map.Entry<String, String> e : renames.entrySet()) {
                String oldPath = e.getKey();
                String newBasename = e.getValue();
                final int current = ++i;
                final String currentLabel = Path.of(oldPath).getFileName() + " → " + newBasename;
                Platform.runLater(() -> {
                    progressBar.setProgress((double) (current - 1) / total);
                    progressLabel.setText(current + " of " + total);
                    currentFileLabel.setText(currentLabel);
                });

                // Snapshot the analysis-cache validity BEFORE renaming; the path
                // is part of the hash so a rename invalidates it otherwise.
                boolean wasAnalysisCached = imageAnalysisService.isAnalysisCached(oldPath);

                FileOperationsService.OperationResult op =
                        fileOperationsService.renameImage(oldPath, newBasename);
                if (op.isSuccess()) {
                    success++;
                    String newPath = op.getMessage();
                    if (wasAnalysisCached) {
                        try {
                            imageAnalysisService.refreshAnalysisHash(newPath);
                            analysisCachePreserved++;
                        } catch (Exception ex) {
                            logger.warn("ResultsPanel", "Failed to refresh analysis hash for " + newPath + ": " + ex.getMessage());
                        }
                    }
                    try {
                        openSearchService.deleteDocument(oldPath);
                        if (indexerService.indexSingleFile(newPath)) {
                            reindexed++;
                        }
                    } catch (Exception ex) {
                        logger.error("ResultsPanel", "Failed to update index for renamed file", ex);
                    }
                } else {
                    failure++;
                    errors.add(op.getMessage());
                }
            }

            final int finalSuccess = success;
            final int finalFailure = failure;
            final int finalReindexed = reindexed;
            final int finalCachePreserved = analysisCachePreserved;
            Platform.runLater(() -> {
                progressStage.close();
                StringBuilder msg = new StringBuilder();
                msg.append("Renamed ").append(finalSuccess).append(" file(s)");
                if (finalFailure > 0) {
                    msg.append(", ").append(finalFailure).append(" failed");
                }
                msg.append(".\nIndex updated: ").append(finalReindexed).append(" file(s) re-indexed.");
                if (finalCachePreserved > 0) {
                    msg.append("\nAnalysis cache preserved for ").append(finalCachePreserved).append(" file(s).");
                }
                if (!errors.isEmpty()) {
                    msg.append("\nErrors: ").append(String.join("; ",
                            errors.subList(0, Math.min(errors.size(), 5))));
                    if (errors.size() > 5) {
                        msg.append(" (+").append(errors.size() - 5).append(" more)");
                    }
                }
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Rename Complete");
                alert.setHeaderText(null);
                alert.setContentText(msg.toString());
                if (getScene() != null && getScene().getWindow() != null) {
                    alert.initOwner(getScene().getWindow());
                }
                alert.showAndWait();
                refresh();
            });
        });
        applyThread.setDaemon(true);
        applyThread.start();
    }

    /**
     * Delete images after a typed confirmation. Operates on either the
     * current selection or the full current result set; deleting the result
     * set requires explicitly typing DELETE to guard against accidents.
     */
    private void deleteSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());

        if (selected.isEmpty() && totalResults == 0) {
            showAlert(Alert.AlertType.WARNING, "No Images", "Select images or run a search first.");
            return;
        }

        BatchSource source = askDeleteConfirm(selected.size(), totalResults);
        if (source == null) return;

        if (source == BatchSource.SELECTED) {
            performDelete(selected);
        } else {
            fetchCurrentResults("Delete", this::performDelete);
        }
    }

    /**
     * Combined source-picker + typed-DELETE confirmation dialog. Returns the
     * chosen source, or null if cancelled. The OK button stays disabled
     * until a source is selected AND the text field contains "DELETE".
     */
    private BatchSource askDeleteConfirm(int selectedCount, long resultsCount) {
        Dialog<BatchSource> dialog = new Dialog<>();
        dialog.setTitle("Confirm Delete");
        dialog.setHeaderText("Permanently delete images");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }

        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Delete");
        okBtn.getStyleClass().add("delete-button");
        okBtn.setDisable(true);

        ToggleGroup group = new ToggleGroup();
        RadioButton selectedRadio = new RadioButton("Selected (" + selectedCount + ")");
        RadioButton resultsRadio = new RadioButton("Current results (" + resultsCount + ")");
        selectedRadio.setToggleGroup(group);
        resultsRadio.setToggleGroup(group);
        selectedRadio.setDisable(selectedCount == 0);
        resultsRadio.setDisable(resultsCount == 0);
        if (selectedCount > 0) {
            selectedRadio.setSelected(true);
        } else {
            resultsRadio.setSelected(true);
        }

        HBox sourceBox = new HBox(15, new Label("Source:"), selectedRadio, resultsRadio);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-font-weight: bold;");

        Label warning = new Label(
                "This will permanently delete the files from disk and remove them from the index. "
                + "This cannot be undone.");
        warning.setWrapText(true);
        warning.setMaxWidth(420);

        Label confirmPrompt = new Label("Type DELETE to confirm:");
        TextField confirmField = new TextField();
        confirmField.setPromptText("DELETE");

        Runnable updateState = () -> {
            long count = selectedRadio.isSelected() ? selectedCount : resultsCount;
            countLabel.setText("Will delete " + count + " image(s).");
            boolean confirmed = "DELETE".equals(confirmField.getText());
            boolean hasSource = group.getSelectedToggle() != null;
            okBtn.setDisable(!(confirmed && hasSource));
        };
        updateState.run();
        selectedRadio.selectedProperty().addListener((o, a, b) -> updateState.run());
        resultsRadio.selectedProperty().addListener((o, a, b) -> updateState.run());
        confirmField.textProperty().addListener((o, a, b) -> updateState.run());

        VBox content = new VBox(10, sourceBox, countLabel, warning, confirmPrompt, confirmField);
        content.setPadding(new Insets(10));
        content.setPrefWidth(450);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                return selectedRadio.isSelected() ? BatchSource.SELECTED : BatchSource.RESULTS;
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }

    private void performDelete(List<ImageMetadata> targets) {
        if (targets.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Images", "There are no images to delete.");
            return;
        }

        List<String> paths = targets.stream()
                .map(ImageMetadata::getFilePath)
                .collect(Collectors.toList());

        FileOperationsService.BatchOperationResult result =
                fileOperationsService.deleteImages(paths, true);

        int indexRemoved = 0;
        for (ImageMetadata metadata : targets) {
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

        if (result.successCount > 0) {
            refresh();
        }
    }

    /**
     * Generate a new image from selected images using Luma AI.
     */
    private void generateFromSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to use as reference.");
            return;
        }

        LumaService lumaService = LumaService.getInstance();
        if (!lumaService.isConfigured()) {
            showAlert(Alert.AlertType.ERROR, "Configuration Required",
                    "Please configure your Luma API key and ImgBB API key in Settings (Image Generation tab).\n\n" +
                    "Luma key: https://lumalabs.ai/dream-machine/api\n" +
                    "ImgBB key (free): https://api.imgbb.com/");
            return;
        }

        // Open generation dialog
        Stage ownerStage = (Stage) getScene().getWindow();
        LumaGenerationDialog dialog = new LumaGenerationDialog(ownerStage, selected);
        dialog.showAndWait();
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
            String provider = ConfigService.getInstance().getAiProvider();
            if ("moondream".equalsIgnoreCase(provider)) {
                showAlert(Alert.AlertType.ERROR, "Moondream Not Available",
                        "Moondream Python dependencies not found.\nInstall with: pip install \"transformers>=4.51,<5\" torch Pillow accelerate\nThen verify the Python path in Settings (AI Analysis tab).");
            } else {
                showAlert(Alert.AlertType.ERROR, "API Key Required",
                        "Please configure your API key in Settings (AI Analysis tab).");
            }
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
        boolean isLocal = "moondream".equalsIgnoreCase(ConfigService.getInstance().getAiProvider());
        String costNote = isLocal ? "\n\nMoondream runs locally — no API costs." : "\n\nNote: API usage incurs costs.";
        confirm.setContentText("This will use " + providerName + " to analyze each image and populate metadata fields (tags, persons, place, rating)." +
                unsupportedMsg + costNote);

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
                        // Merge analysis results with existing metadata (don't overwrite user data)
                        if (result.getTags() != null && !result.getTags().isEmpty()) {
                            for (String tag : result.getTags()) {
                                metadata.addTag(tag);
                            }
                        }
                        // AI-generated "persons" are descriptive appearance strings; merge
                        // into tags so the persons field stays reserved for named people.
                        if (result.getPersons() != null && !result.getPersons().isEmpty()) {
                            for (String person : result.getPersons()) {
                                metadata.addTag(person);
                            }
                        }
                        if (result.getPlace() != null && !result.getPlace().isEmpty()) {
                            if (metadata.getPlace() == null || metadata.getPlace().isEmpty()) {
                                metadata.setPlace(result.getPlace());
                            }
                        }
                        if (result.getRating() != null && !result.getRating().isEmpty()) {
                            if (metadata.getRating() == null || metadata.getRating().isEmpty()) {
                                metadata.setRating(result.getRating());
                            }
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
     * Set callback for when a metadata chip is clicked in the results table.
     * The callback receives (fieldName, value) — e.g. ("persons", "John").
     */
    public void setChipClickCallback(BiConsumer<String, String> callback) {
        this.chipClickCallback = callback;
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
        slideshow.setDeleteCallback(metadata -> Platform.runLater(() -> {
            resultsTable.getItems().remove(metadata);
            resultsTable.refresh();
        }));
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
     * Upload selected images to a cloud remote via rclone.
     */
    private void uploadSelectedImages() {
        List<ImageMetadata> selected = new ArrayList<>(resultsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Selection", "Please select one or more images to upload.");
            return;
        }

        if (!rcloneService.isInstalled()) {
            showAlert(Alert.AlertType.ERROR, "rclone Not Found",
                    "rclone is not installed or not found on PATH.\nInstall from https://rclone.org/install/");
            return;
        }

        showUploadProviderDialog(selected);
    }

    /**
     * Show dialog to pick rclone remote and path, then start upload.
     */
    private void showUploadProviderDialog(List<ImageMetadata> images) {
        List<String> remotes = rcloneService.listRemotes();
        if (remotes.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Remotes Configured",
                    "No rclone remotes found. Configure one with: rclone config");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Upload Selected Images");
        dialog.setHeaderText("Upload " + images.size() + " image(s) to cloud");
        dialog.initModality(Modality.APPLICATION_MODAL);

        // Remote picker
        Label remoteLabel = new Label("Remote:");
        ComboBox<String> remoteCombo = new ComboBox<>();
        remoteCombo.getItems().addAll(remotes);
        String defaultRemote = configService.getRcloneRemoteName();
        if (defaultRemote != null && remotes.contains(defaultRemote)) {
            remoteCombo.setValue(defaultRemote);
        } else if (!remotes.isEmpty()) {
            remoteCombo.setValue(remotes.get(0));
        }
        remoteCombo.setPrefWidth(250);

        // Remote path
        Label pathLabel = new Label("Remote path:");
        TextField pathField = new TextField();
        String defaultPath = configService.getRcloneRemotePath();
        pathField.setText(defaultPath != null ? defaultPath : "");
        pathField.setPromptText("e.g. Photos/2024");
        pathField.setPrefWidth(250);

        // Skip already uploaded checkbox
        CheckBox skipUploadedCheck = new CheckBox("Skip already uploaded files");
        skipUploadedCheck.setSelected(true);
        skipUploadedCheck.setTooltip(new Tooltip("Skip files that have already been uploaded to the selected remote"));

        // Dry run checkbox
        CheckBox dryRunCheck = new CheckBox("Dry run (preview only, don't upload)");

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(remoteLabel, remoteCombo, pathLabel, pathField, skipUploadedCheck, dryRunCheck);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Upload");

        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                String remoteName = remoteCombo.getValue();
                String remotePath = pathField.getText().trim();
                boolean dryRun = dryRunCheck.isSelected();
                boolean skipUploaded = skipUploadedCheck.isSelected();

                if (remoteName == null || remoteName.isEmpty()) {
                    showAlert(Alert.AlertType.WARNING, "No Remote", "Please select a remote.");
                    return;
                }

                uploadImagesInBackground(images, remoteName, remotePath, dryRun, skipUploaded);
            }
        });
    }

    /**
     * Upload images in a background thread with progress dialog.
     */
    private void uploadImagesInBackground(List<ImageMetadata> images, String remoteName,
                                           String remotePath, boolean dryRun, boolean skipUploaded) {
        List<String> filePaths = images.stream()
                .map(ImageMetadata::getFilePath)
                .collect(Collectors.toList());

        // Filter out already-uploaded files if requested
        int skippedCount = 0;
        if (skipUploaded) {
            List<String> filteredPaths = new ArrayList<>();
            for (String path : filePaths) {
                SidecarService.SidecarData sidecar = sidecarService.readSidecar(path);
                if (sidecar != null && sidecar.isUploadedTo(remoteName)) {
                    skippedCount++;
                } else {
                    filteredPaths.add(path);
                }
            }
            if (skippedCount > 0) {
                logger.info("ResultsPanel", "Skipping " + skippedCount + " file(s) already uploaded to " + remoteName);
            }
            if (filteredPaths.isEmpty()) {
                showAlert(Alert.AlertType.INFORMATION, "Nothing to Upload",
                        "All " + skippedCount + " file(s) have already been uploaded to " + remoteName + ".");
                return;
            }
            filePaths = filteredPaths;
        }
        final int finalSkippedCount = skippedCount;
        final int totalSelected = images.size();

        final List<String> uploadFilePaths = filePaths;
        String dryRunLabel = dryRun ? " (DRY RUN)" : "";
        String skipLabel = skippedCount > 0 ? " (" + skippedCount + " skipped)" : "";
        updateStatus("Uploading " + uploadFilePaths.size() + " file(s) to " + remoteName + dryRunLabel + skipLabel + "...");

        // Create progress dialog
        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initStyle(StageStyle.UTILITY);
        progressStage.setTitle("Uploading Images" + dryRunLabel);
        progressStage.setResizable(true);

        VBox progressContent = new VBox(10);
        progressContent.setPadding(new Insets(20));
        progressContent.setAlignment(Pos.CENTER_LEFT);
        progressContent.setPrefWidth(550);
        progressContent.setPrefHeight(350);

        Label titleLabel = new Label("Uploading to " + remoteName + ":" + remotePath + dryRunLabel);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(500);
        progressBar.setPrefHeight(25);

        Label progressLabel = new Label("0 of " + uploadFilePaths.size() + " files");
        progressLabel.setStyle("-fx-font-size: 12px;");

        Label currentFileLabel = new Label("Preparing...");
        currentFileLabel.getStyleClass().add("info-label-small");
        currentFileLabel.setWrapText(true);
        currentFileLabel.setMaxWidth(500);

        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPrefHeight(150);
        outputArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> {
            rcloneService.cancelUpload();
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling...");
            currentFileLabel.setText("Cancelling...");
        });

        progressContent.getChildren().addAll(titleLabel, progressBar, progressLabel,
                currentFileLabel, outputArea, cancelButton);

        javafx.scene.Scene progressScene = new javafx.scene.Scene(progressContent);
        progressStage.setScene(progressScene);
        progressStage.show();

        // Center on parent window
        if (getScene() != null && getScene().getWindow() != null) {
            progressStage.setX(getScene().getWindow().getX() + (getScene().getWindow().getWidth() - 550) / 2);
            progressStage.setY(getScene().getWindow().getY() + (getScene().getWindow().getHeight() - 350) / 2);
        }

        Thread thread = new Thread(() -> {
            RcloneService.UploadResult result = rcloneService.uploadFiles(
                    uploadFilePaths, remoteName, remotePath, dryRun,
                    progress -> Platform.runLater(() -> {
                        double pct = progress.getTotalFiles() > 0
                                ? (double) progress.getCurrentFileIndex() / progress.getTotalFiles()
                                : 0;
                        progressBar.setProgress(pct);
                        progressLabel.setText(progress.getCurrentFileIndex() + " of " + progress.getTotalFiles() + " files");

                        if (progress.getCurrentFileName() != null) {
                            currentFileLabel.setText(progress.getCurrentFileName());
                        }
                        if (progress.getStatusLine() != null) {
                            outputArea.appendText(progress.getStatusLine() + "\n");
                        }
                    }));

            Platform.runLater(() -> {
                progressStage.close();

                String summary;
                if (result.isSuccess()) {
                    String action = dryRun ? "would be uploaded" : "uploaded";
                    summary = (dryRun ? "Dry run complete." : "Upload complete.") +
                            "\nSelected: " + totalSelected + " file(s)" +
                            (finalSkippedCount > 0 ? "\nSkipped: " + finalSkippedCount + " (already uploaded to " + remoteName + ")" : "") +
                            "\nUploaded: " + uploadFilePaths.size() + " file(s) " + action + " to " + remoteName + ":" + remotePath;
                } else {
                    summary = "Upload finished with errors." +
                            "\nSelected: " + totalSelected + " file(s)" +
                            (finalSkippedCount > 0 ? "\nSkipped: " + finalSkippedCount + " (already uploaded to " + remoteName + ")" : "") +
                            "\n" + result.getError();
                }

                updateStatus(dryRun ? "Dry run complete" : "Upload complete");
                showAlert(result.isSuccess() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                        dryRun ? "Dry Run Complete" : "Upload Complete", summary);
            });
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
