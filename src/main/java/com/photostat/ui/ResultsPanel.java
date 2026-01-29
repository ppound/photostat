package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Panel displaying search results in a table with thumbnails.
 */
public class ResultsPanel extends VBox {

    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;
    private final ConfigService configService;
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
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        // Create columns
        createColumns();

        // Selection listener
        resultsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (selectionCallback != null && newVal != null) {
                selectionCallback.accept(newVal);
            }
        });

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

        getChildren().addAll(resultsCountLabel, resultsTable, pagination);
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
}
