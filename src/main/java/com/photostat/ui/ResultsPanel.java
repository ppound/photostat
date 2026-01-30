package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.FileOperationsService;
import com.photostat.services.IndexerService;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ResultsPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.configService = ConfigService.getInstance();
        this.fileOperationsService = FileOperationsService.getInstance();
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
        Button copySelectedBtn = new Button("Copy Selected...");
        copySelectedBtn.setOnAction(e -> copySelectedImages());

        Button moveSelectedBtn = new Button("Move Selected...");
        moveSelectedBtn.setOnAction(e -> moveSelectedImages());

        Button deleteSelectedBtn = new Button("Delete Selected");
        deleteSelectedBtn.setStyle("-fx-text-fill: #cc0000;");
        deleteSelectedBtn.setOnAction(e -> deleteSelectedImages());

        Label selectionLabel = new Label("(Use Ctrl+Click or Shift+Click to select multiple)");
        selectionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        HBox toolbar = new HBox(10, copySelectedBtn, moveSelectedBtn, deleteSelectedBtn, selectionLabel);
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
        // Thumbnail column
        TableColumn<ImageMetadata, ImageView> thumbnailCol = new TableColumn<>("Thumbnail");
        thumbnailCol.setPrefWidth(80);
        thumbnailCol.setCellValueFactory(cellData -> {
            ImageMetadata metadata = cellData.getValue();
            ImageView imageView = new ImageView();
            imageView.setFitWidth(60);
            imageView.setFitHeight(60);
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
                thumbnailCol, filenameCol, cameraCol, dateCol,
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
     * Refresh the current page.
     */
    public void refresh() {
        loadPage(pagination.getCurrentPageIndex());
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
