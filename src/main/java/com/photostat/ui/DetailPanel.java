package com.photostat.ui;

import com.photostat.models.ImageMetadata;
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

    private ImageView previewImage;
    private Label fileNameLabel;
    private GridPane basicInfoGrid;
    private GridPane cameraInfoGrid;
    private GridPane exposureInfoGrid;
    private GridPane gpsInfoGrid;
    private TitledPane allExifPane;
    private TextArea allExifText;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DetailPanel() {
        this.thumbnailService = ThumbnailService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setSpacing(10);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-radius: 5;");

        // Preview image
        previewImage = new ImageView();
        previewImage.setPreserveRatio(true);

        StackPane imageContainer = new StackPane(previewImage);
        imageContainer.setStyle("-fx-background-color: #333;");
        imageContainer.setMinHeight(200);
        imageContainer.setPrefHeight(250);
        imageContainer.setMaxHeight(300);
        imageContainer.setAlignment(Pos.CENTER);

        // Bind image size to container size
        previewImage.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));

        // File name
        fileNameLabel = new Label("No image selected");
        fileNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        fileNameLabel.setWrapText(true);

        // Open button
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
                basicPane, cameraPane, exposurePane, gpsPane, allExifPane
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

        // Update all EXIF
        updateAllExif(metadata);
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
        alert.showAndWait();
    }
}
