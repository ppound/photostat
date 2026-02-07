package com.photostat.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Panel displaying faceted navigation based on search aggregations.
 */
public class FacetsPanel extends VBox {

    private ScrollPane scrollPane;
    private VBox facetsContainer;

    private TitledPane cameraMakePane;
    private TitledPane cameraModelPane;
    private TitledPane lensPane;
    private TitledPane softwarePane;
    private TitledPane fileTypePane;
    private TitledPane isoRangePane;
    private TitledPane yearPane;
    private TitledPane personsPane;
    private TitledPane placePane;
    private TitledPane tagsPane;
    private TitledPane ratingPane;

    private BiConsumer<String, String> filterCallback;

    public FacetsPanel() {
        initializeUI();
    }

    private void initializeUI() {
        setSpacing(5);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-radius: 5;");

        Label title = new Label("Filters");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Create facet panes
        cameraMakePane = createFacetPane("Camera Make", "camera_make");
        cameraModelPane = createFacetPane("Camera Model", "camera_model");
        lensPane = createFacetPane("Lens", "lens_model");
        softwarePane = createFacetPane("Software", "software");
        fileTypePane = createFacetPane("File Type", "file_type");
        isoRangePane = createFacetPane("ISO Range", "iso");
        yearPane = createFacetPane("Year", "year");
        personsPane = createFacetPane("Persons", "persons");
        placePane = createFacetPane("Places", "place");
        tagsPane = createFacetPane("Tags", "tags");
        ratingPane = createFacetPane("Rating", "rating");

        // Default expanded state
        cameraMakePane.setExpanded(true);
        fileTypePane.setExpanded(true);
        tagsPane.setExpanded(true);
        ratingPane.setExpanded(true);

        facetsContainer = new VBox(5);
        facetsContainer.getChildren().addAll(
                cameraMakePane,
                cameraModelPane,
                lensPane,
                softwarePane,
                fileTypePane,
                isoRangePane,
                yearPane,
                new Separator(),
                personsPane,
                placePane,
                tagsPane,
                ratingPane
        );

        scrollPane = new ScrollPane(facetsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Set min height so the panel has a reasonable size
        setMinHeight(150);

        getChildren().addAll(title, new Separator(), scrollPane);
    }

    private TitledPane createFacetPane(String title, String field) {
        TitledPane pane = new TitledPane();
        pane.setText(title);
        pane.setExpanded(false);

        VBox content = new VBox(3);
        content.setPadding(new Insets(5));
        pane.setContent(content);

        return pane;
    }

    /**
     * Update facets with aggregation data from search.
     */
    public void updateFacets(Map<String, Map<String, Long>> aggregations) {
        if (aggregations == null) return;

        updateFacetPane(cameraMakePane, "camera_make", aggregations.get("camera_make"));
        updateFacetPane(cameraModelPane, "camera_model", aggregations.get("camera_model"));
        updateFacetPane(lensPane, "lens_model", aggregations.get("lens_model"));
        updateFacetPane(softwarePane, "software", aggregations.get("software"));
        updateFacetPane(fileTypePane, "file_type", aggregations.get("file_type"));
        updateFacetPane(isoRangePane, "iso", aggregations.get("iso_ranges"));
        updateFacetPane(yearPane, "year", aggregations.get("year"));
        updateFacetPane(personsPane, "persons", aggregations.get("persons"));
        updateFacetPane(placePane, "place", aggregations.get("place"));
        updateFacetPane(tagsPane, "tags", aggregations.get("tags"));
        updateFacetPane(ratingPane, "rating", aggregations.get("rating"));
    }

    private void updateFacetPane(TitledPane pane, String field, Map<String, Long> buckets) {
        VBox content = (VBox) pane.getContent();
        content.getChildren().clear();

        if (buckets == null || buckets.isEmpty()) {
            content.getChildren().add(new Label("No data"));
            return;
        }

        // Sort by count descending and take top 15
        buckets.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(entry -> {
                    String label = formatLabel(entry.getKey());
                    long count = entry.getValue();

                    Hyperlink link = new Hyperlink(String.format("%s (%,d)", label, count));
                    link.setStyle("-fx-padding: 2 0 2 0;");
                    link.setOnAction(e -> {
                        if (filterCallback != null) {
                            filterCallback.accept(field, entry.getKey());
                        }
                    });

                    content.getChildren().add(link);
                });

        // Update pane title with count
        int totalItems = buckets.size();
        pane.setText(pane.getText().replaceAll(" \\(\\d+\\)$", "") + " (" + totalItems + ")");
    }

    private String formatLabel(String value) {
        if (value == null || value.isEmpty()) {
            return "(empty)";
        }

        // Format year labels from date histogram
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return value.substring(0, 4);  // Extract just the year
        }

        // Truncate long labels
        if (value.length() > 30) {
            return value.substring(0, 27) + "...";
        }

        return value;
    }

    /**
     * Set callback for when a facet filter is clicked.
     */
    public void setFilterCallback(BiConsumer<String, String> callback) {
        this.filterCallback = callback;
    }

    /**
     * Clear all facet selections.
     */
    public void clearSelections() {
        // Facets are stateless - selections are handled by SearchPanel
    }
}
