package com.photostat.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Panel containing search controls and filters.
 */
public class SearchPanel extends VBox {

    private TextField searchField;
    private ComboBox<String> cameraMakeCombo;
    private ComboBox<String> cameraModelCombo;
    private ComboBox<String> lensCombo;
    private ComboBox<String> fileTypeCombo;
    private DatePicker dateFromPicker;
    private DatePicker dateToPicker;
    private Spinner<Integer> isoMinSpinner;
    private Spinner<Integer> isoMaxSpinner;
    private Spinner<Double> apertureMinSpinner;
    private Spinner<Double> apertureMaxSpinner;
    private Spinner<Integer> focalLengthMinSpinner;
    private Spinner<Integer> focalLengthMaxSpinner;

    private BiConsumer<String, Map<String, Object>> searchCallback;

    public SearchPanel() {
        initializeUI();
    }

    private void initializeUI() {
        setSpacing(0);
        setPadding(new Insets(0));
        setStyle("-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-radius: 5;");

        // Content container that will be scrollable
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Search field
        Label searchLabel = new Label("Search:");
        searchField = new TextField();
        searchField.setPromptText("Search by filename, camera, lens...");
        searchField.setOnAction(e -> executeSearch());

        HBox searchBox = new HBox(10, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // Filter grid
        GridPane filterGrid = new GridPane();
        filterGrid.setHgap(10);
        filterGrid.setVgap(8);

        int row = 0;

        // Camera Make
        filterGrid.add(new Label("Camera Make:"), 0, row);
        cameraMakeCombo = new ComboBox<>();
        cameraMakeCombo.setEditable(true);
        cameraMakeCombo.setPromptText("All");
        cameraMakeCombo.setPrefWidth(150);
        filterGrid.add(cameraMakeCombo, 1, row++);

        // Camera Model
        filterGrid.add(new Label("Camera Model:"), 0, row);
        cameraModelCombo = new ComboBox<>();
        cameraModelCombo.setEditable(true);
        cameraModelCombo.setPromptText("All");
        cameraModelCombo.setPrefWidth(150);
        filterGrid.add(cameraModelCombo, 1, row++);

        // Lens
        filterGrid.add(new Label("Lens:"), 0, row);
        lensCombo = new ComboBox<>();
        lensCombo.setEditable(true);
        lensCombo.setPromptText("All");
        lensCombo.setPrefWidth(150);
        filterGrid.add(lensCombo, 1, row++);

        // File Type
        filterGrid.add(new Label("File Type:"), 0, row);
        fileTypeCombo = new ComboBox<>();
        fileTypeCombo.getItems().addAll("", ".jpg", ".jpeg", ".png", ".tiff", ".cr2", ".cr3", ".nef", ".arw", ".dng");
        fileTypeCombo.setPromptText("All");
        fileTypeCombo.setPrefWidth(150);
        filterGrid.add(fileTypeCombo, 1, row++);

        // Date range
        TitledPane datePane = new TitledPane();
        datePane.setText("Date Range");
        datePane.setExpanded(false);

        GridPane dateGrid = new GridPane();
        dateGrid.setHgap(10);
        dateGrid.setVgap(8);

        dateFromPicker = new DatePicker();
        dateFromPicker.setPromptText("From");
        dateFromPicker.setPrefWidth(130);
        dateToPicker = new DatePicker();
        dateToPicker.setPromptText("To");
        dateToPicker.setPrefWidth(130);

        dateGrid.add(new Label("From:"), 0, 0);
        dateGrid.add(dateFromPicker, 1, 0);
        dateGrid.add(new Label("To:"), 0, 1);
        dateGrid.add(dateToPicker, 1, 1);
        datePane.setContent(dateGrid);

        // Exposure settings
        TitledPane exposurePane = new TitledPane();
        exposurePane.setText("Exposure Settings");
        exposurePane.setExpanded(false);

        GridPane exposureGrid = new GridPane();
        exposureGrid.setHgap(10);
        exposureGrid.setVgap(8);

        // ISO range
        exposureGrid.add(new Label("ISO:"), 0, 0);
        isoMinSpinner = new Spinner<>(0, 102400, 0, 100);
        isoMinSpinner.setEditable(true);
        isoMinSpinner.setPrefWidth(80);
        isoMaxSpinner = new Spinner<>(0, 102400, 0, 100);
        isoMaxSpinner.setEditable(true);
        isoMaxSpinner.setPrefWidth(80);
        HBox isoBox = new HBox(5, isoMinSpinner, new Label("-"), isoMaxSpinner);
        exposureGrid.add(isoBox, 1, 0);

        // Aperture range
        exposureGrid.add(new Label("Aperture:"), 0, 1);
        apertureMinSpinner = new Spinner<>(0.0, 64.0, 0.0, 0.1);
        apertureMinSpinner.setEditable(true);
        apertureMinSpinner.setPrefWidth(80);
        apertureMaxSpinner = new Spinner<>(0.0, 64.0, 0.0, 0.1);
        apertureMaxSpinner.setEditable(true);
        apertureMaxSpinner.setPrefWidth(80);
        HBox apertureBox = new HBox(5, apertureMinSpinner, new Label("-"), apertureMaxSpinner);
        exposureGrid.add(apertureBox, 1, 1);

        // Focal length range
        exposureGrid.add(new Label("Focal Length:"), 0, 2);
        focalLengthMinSpinner = new Spinner<>(0, 2000, 0, 10);
        focalLengthMinSpinner.setEditable(true);
        focalLengthMinSpinner.setPrefWidth(80);
        focalLengthMaxSpinner = new Spinner<>(0, 2000, 0, 10);
        focalLengthMaxSpinner.setEditable(true);
        focalLengthMaxSpinner.setPrefWidth(80);
        HBox focalBox = new HBox(5, focalLengthMinSpinner, new Label("-"), focalLengthMaxSpinner);
        exposureGrid.add(focalBox, 1, 2);

        exposurePane.setContent(exposureGrid);

        // Buttons
        Button searchButton = new Button("Search");
        searchButton.setDefaultButton(true);
        searchButton.setOnAction(e -> executeSearch());

        Button clearButton = new Button("Clear");
        clearButton.setOnAction(e -> clearFilters());

        HBox buttonBox = new HBox(10, searchButton, clearButton);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        // Add all elements to the content container
        content.getChildren().addAll(
                searchLabel, searchBox,
                new Separator(),
                filterGrid,
                datePane,
                exposurePane,
                buttonBox
        );

        // Wrap content in a ScrollPane
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // Make the ScrollPane fill available space
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().add(scrollPane);
    }

    /**
     * Execute a search with current filters.
     */
    public void executeSearch() {
        if (searchCallback != null) {
            String query = searchField.getText();
            Map<String, Object> filters = buildFilters();
            searchCallback.accept(query, filters);
        }
    }

    /**
     * Build filter map from current selections.
     */
    private Map<String, Object> buildFilters() {
        Map<String, Object> filters = new HashMap<>();

        // Camera make
        String cameraMake = cameraMakeCombo.getValue();
        if (cameraMake != null && !cameraMake.trim().isEmpty()) {
            filters.put("camera_make", cameraMake.trim());
        }

        // Camera model
        String cameraModel = cameraModelCombo.getValue();
        if (cameraModel != null && !cameraModel.trim().isEmpty()) {
            filters.put("camera_model", cameraModel.trim());
        }

        // Lens
        String lens = lensCombo.getValue();
        if (lens != null && !lens.trim().isEmpty()) {
            filters.put("lens_model", lens.trim());
        }

        // File type
        String fileType = fileTypeCombo.getValue();
        if (fileType != null && !fileType.trim().isEmpty()) {
            filters.put("file_type", fileType.trim());
        }

        // Date range
        LocalDate dateFrom = dateFromPicker.getValue();
        if (dateFrom != null) {
            filters.put("date_from", LocalDateTime.of(dateFrom, LocalTime.MIN));
        }
        LocalDate dateTo = dateToPicker.getValue();
        if (dateTo != null) {
            filters.put("date_to", LocalDateTime.of(dateTo, LocalTime.MAX));
        }

        // ISO range
        Integer isoMin = isoMinSpinner.getValue();
        Integer isoMax = isoMaxSpinner.getValue();
        if (isoMin != null && isoMin > 0) {
            filters.put("iso_min", isoMin);
        }
        if (isoMax != null && isoMax > 0) {
            filters.put("iso_max", isoMax);
        }

        // Aperture range
        Double apertureMin = apertureMinSpinner.getValue();
        Double apertureMax = apertureMaxSpinner.getValue();
        if (apertureMin != null && apertureMin > 0) {
            filters.put("aperture_min", apertureMin);
        }
        if (apertureMax != null && apertureMax > 0) {
            filters.put("aperture_max", apertureMax);
        }

        // Focal length range
        Integer focalMin = focalLengthMinSpinner.getValue();
        Integer focalMax = focalLengthMaxSpinner.getValue();
        if (focalMin != null && focalMin > 0) {
            filters.put("focal_length_min", focalMin);
        }
        if (focalMax != null && focalMax > 0) {
            filters.put("focal_length_max", focalMax);
        }

        return filters;
    }

    /**
     * Clear all filters.
     */
    public void clearFilters() {
        searchField.clear();
        cameraMakeCombo.setValue(null);
        cameraModelCombo.setValue(null);
        lensCombo.setValue(null);
        fileTypeCombo.setValue(null);
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        isoMinSpinner.getValueFactory().setValue(0);
        isoMaxSpinner.getValueFactory().setValue(0);
        apertureMinSpinner.getValueFactory().setValue(0.0);
        apertureMaxSpinner.getValueFactory().setValue(0.0);
        focalLengthMinSpinner.getValueFactory().setValue(0);
        focalLengthMaxSpinner.getValueFactory().setValue(0);

        executeSearch();
    }

    /**
     * Add a filter programmatically (from facets).
     */
    public void addFilter(String field, String value) {
        switch (field) {
            case "camera_make":
                cameraMakeCombo.setValue(value);
                break;
            case "camera_model":
                cameraModelCombo.setValue(value);
                break;
            case "lens_model":
                lensCombo.setValue(value);
                break;
            case "file_type":
                fileTypeCombo.setValue(value);
                break;
        }
    }

    /**
     * Update combo boxes with available values from aggregations.
     */
    public void updateFilterOptions(Map<String, Map<String, Long>> aggregations) {
        if (aggregations == null) return;

        // Camera makes
        Map<String, Long> cameraMakes = aggregations.get("camera_make");
        if (cameraMakes != null) {
            String current = cameraMakeCombo.getValue();
            cameraMakeCombo.getItems().clear();
            cameraMakeCombo.getItems().add("");
            cameraMakeCombo.getItems().addAll(cameraMakes.keySet());
            cameraMakeCombo.setValue(current);
        }

        // Camera models
        Map<String, Long> cameraModels = aggregations.get("camera_model");
        if (cameraModels != null) {
            String current = cameraModelCombo.getValue();
            cameraModelCombo.getItems().clear();
            cameraModelCombo.getItems().add("");
            cameraModelCombo.getItems().addAll(cameraModels.keySet());
            cameraModelCombo.setValue(current);
        }

        // Lenses
        Map<String, Long> lenses = aggregations.get("lens_model");
        if (lenses != null) {
            String current = lensCombo.getValue();
            lensCombo.getItems().clear();
            lensCombo.getItems().add("");
            lensCombo.getItems().addAll(lenses.keySet());
            lensCombo.setValue(current);
        }
    }

    /**
     * Set the search callback.
     */
    public void setSearchCallback(BiConsumer<String, Map<String, Object>> callback) {
        this.searchCallback = callback;
    }
}
