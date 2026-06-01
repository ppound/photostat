package com.photostat.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Panel containing search controls and filters.
 */
public class SearchPanel extends VBox {

    private TextField searchField;
    private ComboBox<String> cameraMakeCombo;
    private ComboBox<String> cameraModelCombo;
    private ComboBox<String> lensCombo;
    private ComboBox<String> softwareCombo;
    private ComboBox<String> fileTypeCombo;
    private DatePicker dateFromPicker;
    private DatePicker dateToPicker;
    private CheckBox onThisDayCheck;
    private DatePicker onThisDayPicker;
    private Spinner<Integer> onThisDayWindowSpinner;
    private Spinner<Integer> isoMinSpinner;
    private Spinner<Integer> isoMaxSpinner;
    private Spinner<Double> apertureMinSpinner;
    private Spinner<Double> apertureMaxSpinner;
    private Spinner<Integer> focalLengthMinSpinner;
    private Spinner<Integer> focalLengthMaxSpinner;

    // Custom metadata filters
    private MultiSelectAutoComplete personsSelect;
    private MultiSelectAutoComplete placeSelect;
    private MultiSelectAutoComplete tagsSelect;
    private ComboBox<String> ratingCombo;

    // Store original items for autocomplete filtering
    private Map<ComboBox<String>, List<String>> originalItems = new HashMap<>();

    private BiConsumer<String, Map<String, Object>> searchCallback;

    // Search history for Back navigation
    private record SearchState(String query, Map<String, Object> filters) {}
    private final Deque<SearchState> searchHistory = new ArrayDeque<>();
    private SearchState lastSearchState;
    private Runnable historyChangedCallback;

    public SearchPanel() {
        initializeUI();
    }

    private void initializeUI() {
        setSpacing(0);
        setPadding(new Insets(0));
        getStyleClass().add("panel-background");
        setMinHeight(200);
        setPrefHeight(350);
        setMaxHeight(450);

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
        cameraMakeCombo.setPromptText("All");
        cameraMakeCombo.setPrefWidth(150);
        setupAutoComplete(cameraMakeCombo);
        filterGrid.add(cameraMakeCombo, 1, row++);

        // Camera Model
        filterGrid.add(new Label("Camera Model:"), 0, row);
        cameraModelCombo = new ComboBox<>();
        cameraModelCombo.setPromptText("All");
        cameraModelCombo.setPrefWidth(150);
        setupAutoComplete(cameraModelCombo);
        filterGrid.add(cameraModelCombo, 1, row++);

        // Lens
        filterGrid.add(new Label("Lens:"), 0, row);
        lensCombo = new ComboBox<>();
        lensCombo.setPromptText("All");
        lensCombo.setPrefWidth(150);
        setupAutoComplete(lensCombo);
        filterGrid.add(lensCombo, 1, row++);

        // Software
        filterGrid.add(new Label("Software:"), 0, row);
        softwareCombo = new ComboBox<>();
        softwareCombo.setPromptText("All");
        softwareCombo.setPrefWidth(150);
        setupAutoComplete(softwareCombo);
        filterGrid.add(softwareCombo, 1, row++);

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

        // On This Day quick filter — matches any year on the picked month/day.
        // When enabled, From/To are disabled since the script filter overrides them.
        onThisDayCheck = new CheckBox("On this day:");
        onThisDayPicker = new DatePicker(LocalDate.now());
        onThisDayPicker.setPrefWidth(130);
        onThisDayPicker.setDisable(true);
        onThisDayWindowSpinner = new Spinner<>(0, 30, 0, 1);
        onThisDayWindowSpinner.setEditable(true);
        onThisDayWindowSpinner.setPrefWidth(70);
        onThisDayWindowSpinner.setDisable(true);
        onThisDayCheck.selectedProperty().addListener((obs, oldV, newV) -> {
            onThisDayPicker.setDisable(!newV);
            onThisDayWindowSpinner.setDisable(!newV);
            dateFromPicker.setDisable(newV);
            dateToPicker.setDisable(newV);
        });
        dateGrid.add(onThisDayCheck, 0, 2);
        dateGrid.add(onThisDayPicker, 1, 2);
        dateGrid.add(new Label("± days:"), 0, 3);
        dateGrid.add(onThisDayWindowSpinner, 1, 3);

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

        // Custom metadata filters
        TitledPane customPane = new TitledPane();
        customPane.setText("Custom Metadata");
        customPane.setExpanded(false);

        GridPane customGrid = new GridPane();
        customGrid.setHgap(10);
        customGrid.setVgap(8);

        // Persons filter (multi-select)
        customGrid.add(new Label("Person:"), 0, 0);
        personsSelect = new MultiSelectAutoComplete();
        personsSelect.setPromptText("Type to add...");
        personsSelect.setPrefWidth(150);
        customGrid.add(personsSelect, 1, 0);

        // Place filter (multi-select)
        customGrid.add(new Label("Place:"), 0, 1);
        placeSelect = new MultiSelectAutoComplete();
        placeSelect.setPromptText("Type to add...");
        placeSelect.setPrefWidth(150);
        customGrid.add(placeSelect, 1, 1);

        // Tags filter (multi-select)
        customGrid.add(new Label("Tag:"), 0, 2);
        tagsSelect = new MultiSelectAutoComplete();
        tagsSelect.setPromptText("Type to add...");
        tagsSelect.setPrefWidth(150);
        customGrid.add(tagsSelect, 1, 2);

        // Rating filter (single select - keep as ComboBox)
        customGrid.add(new Label("Rating:"), 0, 3);
        ratingCombo = new ComboBox<>();
        ratingCombo.setPromptText("All");
        ratingCombo.setPrefWidth(150);
        setupAutoComplete(ratingCombo);
        customGrid.add(ratingCombo, 1, 3);

        customPane.setContent(customGrid);

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
                customPane,
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
     * Execute a search with current filters, pushing the previous state onto the history stack.
     */
    public void executeSearch() {
        if (searchCallback != null) {
            // Push the *previous* executed state so Back returns to it
            if (lastSearchState != null) {
                searchHistory.push(lastSearchState);
                if (historyChangedCallback != null) {
                    historyChangedCallback.run();
                }
            }

            // Capture what we're about to execute as the new "last" state
            String currentQuery = searchField.getText();
            Map<String, Object> currentFilters = buildFilters();
            lastSearchState = new SearchState(
                    currentQuery != null ? currentQuery : "",
                    currentFilters);

            searchCallback.accept(currentQuery, currentFilters);
        }
    }

    /**
     * Execute a search without pushing to history (used by goBack).
     */
    private void executeSearchNoHistory() {
        if (searchCallback != null) {
            String query = searchField.getText();
            Map<String, Object> filters = buildFilters();
            lastSearchState = new SearchState(
                    query != null ? query : "", filters);
            searchCallback.accept(query, filters);
        }
    }

    /**
     * Build filter map from current selections.
     */
    private Map<String, Object> buildFilters() {
        Map<String, Object> filters = new HashMap<>();

        // Camera make (use editor text for editable ComboBox)
        String cameraMake = cameraMakeCombo.getEditor().getText();
        if (cameraMake != null && !cameraMake.trim().isEmpty()) {
            filters.put("camera_make", cameraMake.trim());
        }

        // Camera model
        String cameraModel = cameraModelCombo.getEditor().getText();
        if (cameraModel != null && !cameraModel.trim().isEmpty()) {
            filters.put("camera_model", cameraModel.trim());
        }

        // Lens
        String lens = lensCombo.getEditor().getText();
        if (lens != null && !lens.trim().isEmpty()) {
            filters.put("lens_model", lens.trim());
        }

        // Software
        String software = softwareCombo.getEditor().getText();
        if (software != null && !software.trim().isEmpty()) {
            filters.put("software", software.trim());
        }

        // File type
        String fileType = fileTypeCombo.getValue();
        if (fileType != null && !fileType.trim().isEmpty()) {
            filters.put("file_type", fileType.trim());
        }

        // On this day takes precedence over the explicit date range when enabled
        if (onThisDayCheck.isSelected()) {
            LocalDate otd = onThisDayPicker.getValue();
            if (otd != null) {
                int window = onThisDayWindowSpinner.getValue() != null
                        ? onThisDayWindowSpinner.getValue() : 0;
                filters.put("on_this_day",
                        String.format("%02d-%02d:%d", otd.getMonthValue(), otd.getDayOfMonth(), window));
            }
        } else {
            // Date range
            LocalDate dateFrom = dateFromPicker.getValue();
            if (dateFrom != null) {
                filters.put("date_from", LocalDateTime.of(dateFrom, LocalTime.MIN));
            }
            LocalDate dateTo = dateToPicker.getValue();
            if (dateTo != null) {
                filters.put("date_to", LocalDateTime.of(dateTo, LocalTime.MAX));
            }
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

        // Custom metadata filters (multi-select components return lists)
        List<String> persons = personsSelect.getSelectedItems();
        if (persons != null && !persons.isEmpty()) {
            filters.put("persons", persons);
        }

        List<String> places = placeSelect.getSelectedItems();
        if (places != null && !places.isEmpty()) {
            filters.put("place", places);
        }

        List<String> tags = tagsSelect.getSelectedItems();
        if (tags != null && !tags.isEmpty()) {
            filters.put("tags", tags);
        }

        String rating = ratingCombo.getEditor().getText();
        if (rating != null && !rating.trim().isEmpty()) {
            filters.put("rating", rating.trim());
        }

        return filters;
    }

    /**
     * Clear all filter controls without executing a search.
     */
    private void clearFiltersQuiet() {
        searchField.clear();
        cameraMakeCombo.getEditor().clear();
        cameraMakeCombo.setValue(null);
        cameraModelCombo.getEditor().clear();
        cameraModelCombo.setValue(null);
        lensCombo.getEditor().clear();
        lensCombo.setValue(null);
        softwareCombo.getEditor().clear();
        softwareCombo.setValue(null);
        fileTypeCombo.setValue(null);
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        dateFromPicker.setDisable(false);
        dateToPicker.setDisable(false);
        onThisDayCheck.setSelected(false);
        onThisDayPicker.setValue(LocalDate.now());
        onThisDayPicker.setDisable(true);
        onThisDayWindowSpinner.getValueFactory().setValue(0);
        onThisDayWindowSpinner.setDisable(true);
        isoMinSpinner.getValueFactory().setValue(0);
        isoMaxSpinner.getValueFactory().setValue(0);
        apertureMinSpinner.getValueFactory().setValue(0.0);
        apertureMaxSpinner.getValueFactory().setValue(0.0);
        focalLengthMinSpinner.getValueFactory().setValue(0);
        focalLengthMaxSpinner.getValueFactory().setValue(0);
        personsSelect.clear();
        placeSelect.clear();
        tagsSelect.clear();
        ratingCombo.getEditor().clear();
        ratingCombo.setValue(null);

        // Restore full item lists after clearing
        for (ComboBox<String> combo : originalItems.keySet()) {
            combo.getItems().setAll(originalItems.get(combo));
        }
    }

    /**
     * Clear all filters and execute a search.
     */
    public void clearFilters() {
        clearFiltersQuiet();
        executeSearch();
    }

    /**
     * Add a filter programmatically (from facets).
     */
    public void addFilter(String field, String value) {
        switch (field) {
            case "camera_make":
                cameraMakeCombo.getEditor().setText(value);
                break;
            case "camera_model":
                cameraModelCombo.getEditor().setText(value);
                break;
            case "lens_model":
                lensCombo.getEditor().setText(value);
                break;
            case "software":
                softwareCombo.getEditor().setText(value);
                break;
            case "file_type":
                fileTypeCombo.setValue(value);
                break;
            case "iso":
                // Parse ISO range like "ISO 100-200" or "ISO 3200+"
                try {
                    String isoStr = value.replace("ISO ", "");
                    if (isoStr.contains("-")) {
                        String[] parts = isoStr.split("-");
                        int minIso = Integer.parseInt(parts[0].trim());
                        int maxIso = Integer.parseInt(parts[1].trim());
                        isoMinSpinner.getValueFactory().setValue(minIso);
                        isoMaxSpinner.getValueFactory().setValue(maxIso);
                    } else if (isoStr.contains("+")) {
                        int minIso = Integer.parseInt(isoStr.replace("+", "").trim());
                        isoMinSpinner.getValueFactory().setValue(minIso);
                        isoMaxSpinner.getValueFactory().setValue(0); // No upper limit
                    } else {
                        int iso = Integer.parseInt(isoStr.replaceAll("[^0-9]", ""));
                        isoMinSpinner.getValueFactory().setValue(iso);
                        isoMaxSpinner.getValueFactory().setValue(iso);
                    }
                } catch (NumberFormatException ignored) {}
                break;
            case "year":
                // Parse year from date string like "2024-01-01T00:00:00.000Z" or just "2024"
                try {
                    String yearStr = value;
                    if (value.contains("T")) {
                        yearStr = value.substring(0, 4);
                    } else if (value.contains("-")) {
                        yearStr = value.substring(0, 4);
                    }
                    int year = Integer.parseInt(yearStr);
                    dateFromPicker.setValue(LocalDate.of(year, 1, 1));
                    dateToPicker.setValue(LocalDate.of(year, 12, 31));
                } catch (Exception ignored) {}
                break;
            case "persons":
                addToMultiSelect(personsSelect, value);
                break;
            case "place":
                addToMultiSelect(placeSelect, value);
                break;
            case "tags":
                addToMultiSelect(tagsSelect, value);
                break;
            case "rating":
                ratingCombo.getEditor().setText(value);
                break;
            case "on_this_day":
                // Encoded as "MM-dd" or "MM-dd:N"
                try {
                    int colonIdx = value.indexOf(':');
                    String mmdd = colonIdx >= 0 ? value.substring(0, colonIdx) : value;
                    int window = colonIdx >= 0 ? Integer.parseInt(value.substring(colonIdx + 1)) : 0;
                    String[] parts = mmdd.split("-");
                    int month = Integer.parseInt(parts[0]);
                    int day = Integer.parseInt(parts[1]);
                    onThisDayCheck.setSelected(true);
                    onThisDayPicker.setValue(LocalDate.of(LocalDate.now().getYear(), month, day));
                    onThisDayWindowSpinner.getValueFactory().setValue(window);
                } catch (Exception ignored) {}
                break;
        }
    }

    /**
     * Go back to the previous search state.
     */
    @SuppressWarnings("unchecked")
    public void goBack() {
        if (searchHistory.isEmpty()) return;

        SearchState state = searchHistory.pop();
        if (historyChangedCallback != null) {
            historyChangedCallback.run();
        }

        clearFiltersQuiet();

        // Restore query text
        if (state.query() != null && !state.query().isEmpty()) {
            searchField.setText(state.query());
        }

        // Restore filters
        if (state.filters() != null) {
            for (Map.Entry<String, Object> entry : state.filters().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof List) {
                    List<String> listVal = (List<String>) value;
                    for (String item : listVal) {
                        addFilter(key, item);
                    }
                } else if (value instanceof String) {
                    addFilter(key, (String) value);
                } else if (value instanceof LocalDateTime) {
                    if ("date_from".equals(key)) {
                        dateFromPicker.setValue(((LocalDateTime) value).toLocalDate());
                    } else if ("date_to".equals(key)) {
                        dateToPicker.setValue(((LocalDateTime) value).toLocalDate());
                    }
                } else if (value instanceof Integer) {
                    switch (key) {
                        case "iso_min" -> isoMinSpinner.getValueFactory().setValue((Integer) value);
                        case "iso_max" -> isoMaxSpinner.getValueFactory().setValue((Integer) value);
                        case "focal_length_min" -> focalLengthMinSpinner.getValueFactory().setValue((Integer) value);
                        case "focal_length_max" -> focalLengthMaxSpinner.getValueFactory().setValue((Integer) value);
                    }
                } else if (value instanceof Double) {
                    switch (key) {
                        case "aperture_min" -> apertureMinSpinner.getValueFactory().setValue((Double) value);
                        case "aperture_max" -> apertureMaxSpinner.getValueFactory().setValue((Double) value);
                    }
                }
            }
        }

        executeSearchNoHistory();
    }

    /**
     * Returns true if there is search history to go back to.
     */
    public boolean hasHistory() {
        return !searchHistory.isEmpty();
    }

    /**
     * Set callback invoked when search history changes (for enabling/disabling Back button).
     */
    public void setHistoryChangedCallback(Runnable callback) {
        this.historyChangedCallback = callback;
    }

    /**
     * Update combo boxes with available values from aggregations.
     */
    public void updateFilterOptions(Map<String, Map<String, Long>> aggregations) {
        if (aggregations == null) return;

        // Camera makes
        Map<String, Long> cameraMakes = aggregations.get("camera_make");
        if (cameraMakes != null) {
            updateOriginalItems(cameraMakeCombo, new ArrayList<>(cameraMakes.keySet()));
        }

        // Camera models
        Map<String, Long> cameraModels = aggregations.get("camera_model");
        if (cameraModels != null) {
            updateOriginalItems(cameraModelCombo, new ArrayList<>(cameraModels.keySet()));
        }

        // Lenses
        Map<String, Long> lenses = aggregations.get("lens_model");
        if (lenses != null) {
            updateOriginalItems(lensCombo, new ArrayList<>(lenses.keySet()));
        }

        // Software
        Map<String, Long> software = aggregations.get("software");
        if (software != null) {
            updateOriginalItems(softwareCombo, new ArrayList<>(software.keySet()));
        }

        // Persons (multi-select)
        Map<String, Long> persons = aggregations.get("persons");
        if (persons != null) {
            personsSelect.setItems(new ArrayList<>(persons.keySet()));
        }

        // Places (multi-select)
        Map<String, Long> places = aggregations.get("place");
        if (places != null) {
            placeSelect.setItems(new ArrayList<>(places.keySet()));
        }

        // Tags (multi-select)
        Map<String, Long> tags = aggregations.get("tags");
        if (tags != null) {
            tagsSelect.setItems(new ArrayList<>(tags.keySet()));
        }

        // Rating
        Map<String, Long> ratings = aggregations.get("rating");
        if (ratings != null) {
            updateOriginalItems(ratingCombo, new ArrayList<>(ratings.keySet()));
        }
    }

    /**
     * Add a value to a multi-select component without removing existing selections.
     */
    private void addToMultiSelect(MultiSelectAutoComplete multiSelect, String value) {
        List<String> currentItems = new ArrayList<>(multiSelect.getSelectedItems());
        if (!currentItems.contains(value)) {
            currentItems.add(value);
            multiSelect.setSelectedItems(currentItems);
        }
    }

    /**
     * Set the search callback.
     */
    public void setSearchCallback(BiConsumer<String, Map<String, Object>> callback) {
        this.searchCallback = callback;
    }

    /**
     * Setup autocomplete filtering for a ComboBox.
     * Filters dropdown items as user types.
     */
    private void setupAutoComplete(ComboBox<String> comboBox) {
        comboBox.setEditable(true);

        // Store reference to original items
        originalItems.put(comboBox, new ArrayList<>());

        // Track if we're updating programmatically to prevent auto-show
        comboBox.getProperties().put("updating", false);

        // Add key listener to filter items when typing
        comboBox.getEditor().setOnKeyReleased(event -> {
            // Only filter on actual character keys, not navigation
            if (event.getCode().isLetterKey() || event.getCode().isDigitKey() ||
                event.getCode() == KeyCode.BACK_SPACE || event.getCode() == KeyCode.DELETE) {

                List<String> allItems = originalItems.get(comboBox);
                if (allItems == null || allItems.isEmpty()) {
                    return;
                }

                String newText = comboBox.getEditor().getText();
                if (newText == null || newText.isEmpty()) {
                    comboBox.getItems().setAll(allItems);
                    comboBox.show();
                } else {
                    String lowerTyped = newText.toLowerCase();
                    List<String> filtered = allItems.stream()
                            .filter(item -> item != null && !item.isEmpty() &&
                                    item.toLowerCase().contains(lowerTyped))
                            .collect(Collectors.toList());

                    if (!filtered.isEmpty()) {
                        comboBox.getItems().setAll(filtered);
                        comboBox.show();
                    }
                }
            }
        });

        // When showing dropdown, make sure items are populated and filtered
        comboBox.setOnShowing(event -> {
            // Don't interfere if we're updating programmatically
            if (Boolean.TRUE.equals(comboBox.getProperties().get("updating"))) {
                return;
            }

            String currentText = comboBox.getEditor().getText();
            List<String> allItems = originalItems.get(comboBox);

            if (allItems == null || allItems.isEmpty()) {
                return;
            }

            if (currentText == null || currentText.isEmpty()) {
                comboBox.getItems().setAll(allItems);
            } else {
                String lowerTyped = currentText.toLowerCase();
                List<String> filtered = allItems.stream()
                        .filter(item -> item != null && !item.isEmpty() &&
                                item.toLowerCase().contains(lowerTyped))
                        .collect(Collectors.toList());
                if (!filtered.isEmpty()) {
                    comboBox.getItems().setAll(filtered);
                } else {
                    comboBox.getItems().setAll(allItems);
                }
            }
        });
    }

    /**
     * Update the original items for a ComboBox (for autocomplete filtering).
     * Preserves current editor text.
     */
    private void updateOriginalItems(ComboBox<String> comboBox, List<String> items) {
        // Mark as updating to prevent auto-show behavior
        comboBox.getProperties().put("updating", true);

        // Preserve current text
        String currentText = comboBox.getEditor().getText();

        List<String> itemList = new ArrayList<>();
        itemList.add(""); // Empty option for "All"
        itemList.addAll(items);
        originalItems.put(comboBox, itemList);

        // Set items without triggering show
        comboBox.getItems().setAll(itemList);

        // Restore the text
        if (currentText != null && !currentText.isEmpty()) {
            comboBox.getEditor().setText(currentText);
        }

        // Done updating
        comboBox.getProperties().put("updating", false);
    }
}
