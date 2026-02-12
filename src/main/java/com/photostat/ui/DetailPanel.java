package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.SidecarService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Panel displaying image preview and detailed metadata.
 */
public class DetailPanel extends VBox {

    private final ThumbnailService thumbnailService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;
    private final LoggingService logger;

    private ImageView previewImage;
    private Label fileNameLabel;
    private GridPane basicInfoGrid;
    private GridPane cameraInfoGrid;
    private GridPane exposureInfoGrid;
    private GridPane gpsInfoGrid;
    private TitledPane allExifPane;
    private TextArea allExifText;

    // Custom metadata fields
    private TextField personsField;
    private TextField placeField;
    private TextField tagsField;
    private TextField ratingField;
    private Button saveMetadataButton;
    private Button copyMetadataButton;
    private Button pasteMetadataButton;
    private Runnable metadataSavedCallback;
    private java.util.function.Consumer<String> statusCallback;

    // Clipboard for custom metadata copy/paste
    private static String copiedPersons = null;
    private static String copiedPlace = null;
    private static String copiedTags = null;
    private static String copiedRating = null;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DetailPanel() {
        this.thumbnailService = ThumbnailService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setSpacing(10);
        setPadding(new Insets(10));
        getStyleClass().add("panel-background");

        // Preview image
        previewImage = new ImageView();
        previewImage.setPreserveRatio(true);

        StackPane imageContainer = new StackPane(previewImage);
        imageContainer.getStyleClass().add("image-container-bg");
        imageContainer.setMinHeight(300);
        imageContainer.setPrefHeight(400);
        imageContainer.setMaxHeight(500);
        imageContainer.setAlignment(Pos.CENTER);

        // Bind image size to container size
        previewImage.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));

        // File name
        fileNameLabel = new Label("No image selected");
        fileNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        fileNameLabel.setWrapText(true);

        // Action buttons
        Button openButton = new Button("Open in Viewer");
        openButton.setOnAction(e -> openCurrentImage());

        Button openFolderButton = new Button("Open Folder");
        openFolderButton.setOnAction(e -> openCurrentFolder());

        HBox buttonBox = new HBox(10, openButton, openFolderButton);

        // Scrollable content
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox detailsContent = new VBox(10);
        detailsContent.setPadding(new Insets(5));

        // Basic info section
        TitledPane basicPane = new TitledPane();
        basicPane.setText("File Information");
        basicPane.setExpanded(true);
        basicInfoGrid = createInfoGrid();
        basicPane.setContent(basicInfoGrid);

        // Camera info section
        TitledPane cameraPane = new TitledPane();
        cameraPane.setText("Camera");
        cameraPane.setExpanded(true);
        cameraInfoGrid = createInfoGrid();
        cameraPane.setContent(cameraInfoGrid);

        // Exposure info section
        TitledPane exposurePane = new TitledPane();
        exposurePane.setText("Exposure Settings");
        exposurePane.setExpanded(true);
        exposureInfoGrid = createInfoGrid();
        exposurePane.setContent(exposureInfoGrid);

        // GPS info section
        TitledPane gpsPane = new TitledPane();
        gpsPane.setText("GPS Location");
        gpsPane.setExpanded(false);
        gpsInfoGrid = createInfoGrid();
        gpsPane.setContent(gpsInfoGrid);

        // Custom metadata section
        TitledPane customPane = new TitledPane();
        customPane.setText("Custom Metadata");
        customPane.setExpanded(true);
        VBox customContent = createCustomMetadataPane();
        customPane.setContent(customContent);

        // All EXIF data section
        allExifPane = new TitledPane();
        allExifPane.setText("All EXIF Data");
        allExifPane.setExpanded(false);
        allExifText = new TextArea();
        allExifText.setEditable(false);
        allExifText.setWrapText(true);
        allExifText.setPrefRowCount(15);
        allExifPane.setContent(allExifText);

        detailsContent.getChildren().addAll(
                basicPane, cameraPane, exposurePane, gpsPane, customPane, allExifPane
        );

        scrollPane.setContent(detailsContent);

        getChildren().addAll(
                imageContainer,
                fileNameLabel,
                buttonBox,
                new Separator(),
                scrollPane
        );
    }

    private GridPane createInfoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.setPadding(new Insets(5));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(80);
        labelCol.setPrefWidth(100);

        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(labelCol, valueCol);

        return grid;
    }

    private VBox createCustomMetadataPane() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(5));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(60);
        labelCol.setPrefWidth(70);

        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(labelCol, valueCol);

        int row = 0;

        // Persons field
        Label personsLabel = new Label("Persons:");
        personsLabel.setStyle("-fx-font-weight: bold;");
        personsField = new TextField();
        personsField.setPromptText("Comma-separated names");
        grid.add(personsLabel, 0, row);
        grid.add(personsField, 1, row++);

        // Place field
        Label placeLabel = new Label("Place:");
        placeLabel.setStyle("-fx-font-weight: bold;");
        placeField = new TextField();
        placeField.setPromptText("Location name");
        grid.add(placeLabel, 0, row);
        grid.add(placeField, 1, row++);

        // Tags field
        Label tagsLabel = new Label("Tags:");
        tagsLabel.setStyle("-fx-font-weight: bold;");
        tagsField = new TextField();
        tagsField.setPromptText("Comma-separated tags");
        grid.add(tagsLabel, 0, row);
        grid.add(tagsField, 1, row++);

        // Rating field
        Label ratingLabel = new Label("Rating:");
        ratingLabel.setStyle("-fx-font-weight: bold;");
        ratingField = new TextField();
        ratingField.setPromptText("e.g., *, **, ***, ****, *****");
        grid.add(ratingLabel, 0, row);
        grid.add(ratingField, 1, row++);

        // Buttons
        saveMetadataButton = new Button("Save");
        saveMetadataButton.setOnAction(e -> saveCustomMetadata());
        saveMetadataButton.setDisable(true);

        copyMetadataButton = new Button("Copy");
        copyMetadataButton.setOnAction(e -> copyCustomMetadata());
        copyMetadataButton.setDisable(true);
        copyMetadataButton.setTooltip(new Tooltip("Copy custom metadata to clipboard"));

        pasteMetadataButton = new Button("Paste");
        pasteMetadataButton.setOnAction(e -> pasteCustomMetadata());
        pasteMetadataButton.setDisable(true);
        pasteMetadataButton.setTooltip(new Tooltip("Paste custom metadata from clipboard"));

        HBox buttonBox = new HBox(5, saveMetadataButton, copyMetadataButton, pasteMetadataButton);
        buttonBox.setPadding(new Insets(5, 0, 0, 0));

        content.getChildren().addAll(grid, buttonBox);

        return content;
    }

    private void saveCustomMetadata() {
        if (currentMetadata == null) {
            logger.warn("DetailPanel", "saveCustomMetadata called but currentMetadata is null");
            return;
        }

        logger.info("DetailPanel", "Saving custom metadata for: " + currentMetadata.getFilePath());

        // Parse comma-separated values
        String personsText = personsField.getText().trim();
        String placeText = placeField.getText().trim();
        String tagsText = tagsField.getText().trim();
        String ratingText = ratingField.getText().trim();

        logger.debug("DetailPanel", "Persons: " + personsText);
        logger.debug("DetailPanel", "Place: " + placeText);
        logger.debug("DetailPanel", "Tags: " + tagsText);
        logger.debug("DetailPanel", "Rating: " + ratingText);

        // Update metadata object - use setPersons with new list to handle null safely
        java.util.List<String> newPersons = new java.util.ArrayList<>();
        if (!personsText.isEmpty()) {
            for (String person : personsText.split(",")) {
                String trimmed = person.trim();
                if (!trimmed.isEmpty() && !newPersons.contains(trimmed)) {
                    newPersons.add(trimmed);
                }
            }
        }
        currentMetadata.setPersons(newPersons);

        currentMetadata.setPlace(placeText.isEmpty() ? null : placeText);

        // Use setTags with new list to handle null safely
        java.util.List<String> newTags = new java.util.ArrayList<>();
        if (!tagsText.isEmpty()) {
            for (String tag : tagsText.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty() && !newTags.contains(trimmed)) {
                    newTags.add(trimmed);
                }
            }
        }
        currentMetadata.setTags(newTags);

        currentMetadata.setRating(ratingText.isEmpty() ? null : ratingText);

        // Save to OpenSearch and sidecar file
        new Thread(() -> {
            try {
                logger.info("DetailPanel", "Calling updateDocument...");
                openSearchService.updateDocument(currentMetadata);
                logger.info("DetailPanel", "updateDocument completed successfully");

                // Write sidecar file (if enabled in config)
                boolean sidecarWritten = sidecarService.writeSidecar(currentMetadata);
                if (sidecarWritten) {
                    logger.info("DetailPanel", "Sidecar file written successfully");
                }

                Platform.runLater(() -> {
                    updateStatus("Metadata saved successfully");
                    if (metadataSavedCallback != null) {
                        logger.info("DetailPanel", "Triggering metadataSavedCallback (search refresh)");
                        metadataSavedCallback.run();
                    }
                });
            } catch (Exception e) {
                logger.error("DetailPanel", "Failed to save metadata", e);
                Platform.runLater(() -> showError("Save Failed", "Failed to save metadata: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Copy custom metadata fields to clipboard.
     */
    private void copyCustomMetadata() {
        copiedPersons = personsField.getText().trim();
        copiedPlace = placeField.getText().trim();
        copiedTags = tagsField.getText().trim();
        copiedRating = ratingField.getText().trim();

        logger.info("DetailPanel", "Copied custom metadata - Persons: " + copiedPersons +
                ", Place: " + copiedPlace + ", Tags: " + copiedTags + ", Rating: " + copiedRating);

        updateStatus("Metadata copied to clipboard");
    }

    /**
     * Paste custom metadata from clipboard into fields.
     */
    private void pasteCustomMetadata() {
        if (copiedPersons == null && copiedPlace == null && copiedTags == null && copiedRating == null) {
            updateStatus("Nothing to paste - copy metadata first");
            return;
        }

        // Paste the copied values into the fields
        if (copiedPersons != null) {
            personsField.setText(copiedPersons);
        }
        if (copiedPlace != null) {
            placeField.setText(copiedPlace);
        }
        if (copiedTags != null) {
            tagsField.setText(copiedTags);
        }
        if (copiedRating != null) {
            ratingField.setText(copiedRating);
        }

        logger.info("DetailPanel", "Pasted custom metadata - Persons: " + copiedPersons +
                ", Place: " + copiedPlace + ", Tags: " + copiedTags + ", Rating: " + copiedRating);

        updateStatus("Metadata pasted - click Save to apply");
    }

    /**
     * Check if there is copied metadata available.
     */
    private static boolean hasClipboardData() {
        return copiedPersons != null || copiedPlace != null || copiedTags != null || copiedRating != null;
    }

    /**
     * Update the rating field display when rating is changed externally (e.g., keyboard shortcut).
     */
    public void updateRatingDisplay(ImageMetadata metadata) {
        if (metadata != null && metadata == currentMetadata) {
            ratingField.setText(metadata.getRating() != null ? metadata.getRating() : "");
        }
    }

    /**
     * Set callback for when metadata is saved (to refresh search results).
     */
    public void setMetadataSavedCallback(Runnable callback) {
        this.metadataSavedCallback = callback;
    }

    /**
     * Set callback for status bar updates.
     */
    public void setStatusCallback(java.util.function.Consumer<String> callback) {
        this.statusCallback = callback;
    }

    /**
     * Update the status bar with a message.
     */
    private void updateStatus(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();  // Use show() instead of showAndWait() to avoid blocking
    }

    /**
     * Display metadata for the given image.
     */
    public void showMetadata(ImageMetadata metadata) {
        if (metadata == null) {
            clearDisplay();
            currentMetadata = null;
            return;
        }

        // Store current metadata for actions
        currentMetadata = metadata;

        // Update filename
        fileNameLabel.setText(metadata.getFileName());

        // Load preview image
        loadPreviewImage(metadata.getFilePath());

        // Update basic info
        updateBasicInfo(metadata);

        // Update camera info
        updateCameraInfo(metadata);

        // Update exposure info
        updateExposureInfo(metadata);

        // Update GPS info
        updateGpsInfo(metadata);

        // Update custom metadata
        updateCustomMetadata(metadata);

        // Update all EXIF
        updateAllExif(metadata);
    }

    private void updateCustomMetadata(ImageMetadata metadata) {
        // Enable buttons
        saveMetadataButton.setDisable(false);
        copyMetadataButton.setDisable(false);
        pasteMetadataButton.setDisable(!hasClipboardData());

        // Populate fields
        personsField.setText(metadata.getPersonsString());
        placeField.setText(metadata.getPlace() != null ? metadata.getPlace() : "");
        tagsField.setText(metadata.getTagsString());
        ratingField.setText(metadata.getRating() != null ? metadata.getRating() : "");
    }

    private void loadPreviewImage(String filePath) {
        thumbnailService.getThumbnailAsync(filePath, (path, thumbnail) -> {
            Platform.runLater(() -> {
                if (thumbnail != null) {
                    previewImage.setImage(thumbnail);
                } else {
                    previewImage.setImage(null);
                }
            });
        });
    }

    private void updateBasicInfo(ImageMetadata metadata) {
        basicInfoGrid.getChildren().clear();
        int row = 0;

        addInfoRow(basicInfoGrid, row++, "File:", metadata.getFileName());
        addInfoRow(basicInfoGrid, row++, "Path:", truncatePath(metadata.getFilePath()));
        addInfoRow(basicInfoGrid, row++, "Size:", metadata.getFileSizeString());
        addInfoRow(basicInfoGrid, row++, "Type:", metadata.getFileType() != null ?
                metadata.getFileType().toUpperCase() : "");
        addInfoRow(basicInfoGrid, row++, "Dimensions:", metadata.getDimensionsString());

        if (metadata.getDateTaken() != null) {
            addInfoRow(basicInfoGrid, row++, "Date Taken:", metadata.getDateTaken().format(DATE_FORMAT));
        }
        if (metadata.getDateIndexed() != null) {
            addInfoRow(basicInfoGrid, row++, "Indexed:", metadata.getDateIndexed().format(DATE_FORMAT));
        }
    }

    private void updateCameraInfo(ImageMetadata metadata) {
        cameraInfoGrid.getChildren().clear();
        int row = 0;

        if (metadata.getCameraMake() != null) {
            addInfoRow(cameraInfoGrid, row++, "Make:", metadata.getCameraMake());
        }
        if (metadata.getCameraModel() != null) {
            addInfoRow(cameraInfoGrid, row++, "Model:", metadata.getCameraModel());
        }
        if (metadata.getLensModel() != null) {
            addInfoRow(cameraInfoGrid, row++, "Lens:", metadata.getLensModel());
        }
        if (metadata.getSoftware() != null) {
            addInfoRow(cameraInfoGrid, row++, "Software:", metadata.getSoftware());
        }
        if (metadata.getArtist() != null) {
            addInfoRow(cameraInfoGrid, row++, "Artist:", metadata.getArtist());
        }
        if (metadata.getCopyright() != null) {
            addInfoRow(cameraInfoGrid, row++, "Copyright:", metadata.getCopyright());
        }
        if (metadata.getOrientation() != null) {
            addInfoRow(cameraInfoGrid, row++, "Orientation:", metadata.getOrientation());
        }
    }

    private void updateExposureInfo(ImageMetadata metadata) {
        exposureInfoGrid.getChildren().clear();
        int row = 0;

        if (metadata.getIso() != null) {
            addInfoRow(exposureInfoGrid, row++, "ISO:", metadata.getIso().toString());
        }
        if (metadata.getAperture() != null) {
            addInfoRow(exposureInfoGrid, row++, "Aperture:", metadata.getApertureString());
        }
        if (metadata.getShutterSpeed() != null) {
            addInfoRow(exposureInfoGrid, row++, "Shutter:", metadata.getShutterSpeed());
        }
        if (metadata.getFocalLength() != null) {
            addInfoRow(exposureInfoGrid, row++, "Focal Length:", metadata.getFocalLengthString());
        }
    }

    private void updateGpsInfo(ImageMetadata metadata) {
        gpsInfoGrid.getChildren().clear();
        int row = 0;

        if (metadata.getGpsLatitude() != null && metadata.getGpsLongitude() != null) {
            addInfoRow(gpsInfoGrid, row++, "Latitude:", String.format("%.6f", metadata.getGpsLatitude()));
            addInfoRow(gpsInfoGrid, row++, "Longitude:", String.format("%.6f", metadata.getGpsLongitude()));

            // Add link to open in maps
            Hyperlink mapsLink = new Hyperlink("Open in Google Maps");
            mapsLink.setOnAction(e -> openInMaps(metadata.getGpsLatitude(), metadata.getGpsLongitude()));
            gpsInfoGrid.add(mapsLink, 0, row, 2, 1);
        } else {
            addInfoRow(gpsInfoGrid, row++, "", "No GPS data available");
        }
    }

    private void updateAllExif(ImageMetadata metadata) {
        Map<String, Object> allExif = metadata.getAllExif();
        if (allExif == null || allExif.isEmpty()) {
            allExifText.setText("No additional EXIF data");
            return;
        }

        StringBuilder sb = new StringBuilder();
        allExif.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append(entry.getKey()).append(": ");
                    Object value = entry.getValue();
                    if (value != null) {
                        String valueStr = value.toString();
                        if (valueStr.length() > 100) {
                            valueStr = valueStr.substring(0, 100) + "...";
                        }
                        sb.append(valueStr);
                    }
                    sb.append("\n");
                });

        allExifText.setText(sb.toString());
    }

    private void addInfoRow(GridPane grid, int row, String label, String value) {
        Label labelNode = new Label(label);
        labelNode.setStyle("-fx-font-weight: bold;");

        Label valueNode = new Label(value != null ? value : "");
        valueNode.setWrapText(true);

        grid.add(labelNode, 0, row);
        grid.add(valueNode, 1, row);
    }

    private String truncatePath(String path) {
        if (path == null) return "";
        if (path.length() <= 50) return path;

        // Show beginning and end
        return path.substring(0, 20) + "..." + path.substring(path.length() - 27);
    }

    private void clearDisplay() {
        fileNameLabel.setText("No image selected");
        previewImage.setImage(null);
        basicInfoGrid.getChildren().clear();
        cameraInfoGrid.getChildren().clear();
        exposureInfoGrid.getChildren().clear();
        gpsInfoGrid.getChildren().clear();
        allExifText.clear();
        personsField.clear();
        placeField.clear();
        tagsField.clear();
        ratingField.clear();
        saveMetadataButton.setDisable(true);
        copyMetadataButton.setDisable(true);
        pasteMetadataButton.setDisable(true);
    }

    private String currentFilePath;

    private void openCurrentImage() {
        ImageMetadata metadata = getCurrentMetadata();
        if (metadata != null && metadata.getFilePath() != null) {
            String filePath = metadata.getFilePath();
            new Thread(() -> {
                try {
                    java.awt.Desktop.getDesktop().open(new File(filePath));
                } catch (Exception e) {
                    Platform.runLater(() -> showError("Cannot open file", e.getMessage()));
                }
            }).start();
        }
    }

    private void openCurrentFolder() {
        ImageMetadata metadata = getCurrentMetadata();
        if (metadata != null && metadata.getFilePath() != null) {
            File file = new File(metadata.getFilePath());
            File parentDir = file.getParentFile();
            new Thread(() -> {
                try {
                    java.awt.Desktop.getDesktop().open(parentDir);
                } catch (Exception e) {
                    Platform.runLater(() -> showError("Cannot open folder", e.getMessage()));
                }
            }).start();
        }
    }

    private void clearDetails() {
        currentMetadata = null;
        previewImage.setImage(null);
        fileNameLabel.setText("Select an image to view details");
        basicInfoGrid.getChildren().clear();
        cameraInfoGrid.getChildren().clear();
        exposureInfoGrid.getChildren().clear();
        gpsInfoGrid.getChildren().clear();
        allExifText.clear();
        personsField.clear();
        placeField.clear();
        tagsField.clear();
        ratingField.clear();
    }

    private void openInMaps(double lat, double lon) {
        new Thread(() -> {
            try {
                String url = String.format("https://www.google.com/maps?q=%.6f,%.6f", lat, lon);
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            } catch (Exception e) {
                Platform.runLater(() -> showError("Cannot open browser", e.getMessage()));
            }
        }).start();
    }

    private ImageMetadata currentMetadata;

    public void showMetadataAndStore(ImageMetadata metadata) {
        this.currentMetadata = metadata;
        showMetadata(metadata);
    }

    private ImageMetadata getCurrentMetadata() {
        return currentMetadata;
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();  // Use show() instead of showAndWait() to avoid blocking
    }
}
