package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.SidecarService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.File;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Panel displaying photos organized chronologically by year and month with thumbnail grids.
 */
public class TimelinePanel extends BorderPane {

    private static final int INITIAL_BATCH = 20;
    private static final int LOAD_MORE_BATCH = 40;
    private static final int EXPANDED_YEARS = 2;

    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;
    private final ConfigService configService;
    private final LoggingService logger;

    private final DetailPanel detailPanel;
    private final VBox contentBox;
    private Consumer<String> statusCallback;
    private int thumbnailSize;

    // Track expanded years and their content nodes for collapse/expand
    private final Map<String, VBox> yearContentMap = new LinkedHashMap<>();
    private final Set<String> expandedYears = new HashSet<>();


    public TimelinePanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
        this.thumbnailSize = configService.getThumbnailSize();

        // Create detail panel for right side
        detailPanel = new DetailPanel();
        detailPanel.setStatusCallback(status -> {
            if (statusCallback != null) statusCallback.accept(status);
        });

        // Main content area
        contentBox = new VBox(5);
        contentBox.setPadding(new Insets(10));

        Label placeholder = new Label("Click refresh or switch to this tab to load timeline.");
        placeholder.setStyle("-fx-text-fill: #888;");
        contentBox.getChildren().add(placeholder);

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // SplitPane with timeline on left, detail on right
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(scrollPane, detailPanel);
        splitPane.setDividerPositions(0.75);
        SplitPane.setResizableWithParent(detailPanel, false);
        detailPanel.setMinWidth(300);
        detailPanel.setPrefWidth(350);
        detailPanel.setMaxWidth(500);

        setCenter(splitPane);
    }

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    public void refresh() {
        updateStatus("Loading timeline...");
        thumbnailSize = configService.getThumbnailSize();

        Thread thread = new Thread(() -> {
            try {
                Map<String, Map<String, Long>> chartData = openSearchService.getChartData();
                Map<String, Long> monthly = chartData.get("monthly");

                if (monthly == null || monthly.isEmpty()) {
                    Platform.runLater(() -> {
                        contentBox.getChildren().clear();
                        Label empty = new Label("No photos with dates found in the index.");
                        empty.setStyle("-fx-text-fill: #888;");
                        contentBox.getChildren().add(empty);
                        updateStatus("Timeline: no dated photos found");
                    });
                    return;
                }

                // Group month keys by year, sorted descending
                // Keys are like "2024-03-01T00:00:00.000Z"
                TreeMap<String, TreeMap<String, Long>> yearMonths = new TreeMap<>(Comparator.reverseOrder());
                Map<String, Long> yearTotals = new TreeMap<>(Comparator.reverseOrder());

                for (Map.Entry<String, Long> entry : monthly.entrySet()) {
                    String key = entry.getKey();
                    long count = entry.getValue();
                    if (count == 0) continue;

                    // Extract year and month from the key
                    String year = key.substring(0, 4);
                    yearMonths.computeIfAbsent(year, k -> new TreeMap<>(Comparator.reverseOrder()));
                    yearMonths.get(year).put(key, count);
                    yearTotals.merge(year, count, Long::sum);
                }

                // Determine which years to expand by default
                List<String> sortedYears = new ArrayList<>(yearMonths.keySet());
                Set<String> defaultExpanded = new HashSet<>();
                for (int i = 0; i < Math.min(EXPANDED_YEARS, sortedYears.size()); i++) {
                    defaultExpanded.add(sortedYears.get(i));
                }

                Platform.runLater(() -> {
                    contentBox.getChildren().clear();
                    yearContentMap.clear();
                    expandedYears.clear();
                    expandedYears.addAll(defaultExpanded);

                    for (String year : sortedYears) {
                        TreeMap<String, Long> months = yearMonths.get(year);
                        long yearTotal = yearTotals.getOrDefault(year, 0L);
                        boolean expanded = defaultExpanded.contains(year);
                        contentBox.getChildren().add(buildYearSection(year, months, yearTotal, expanded));
                    }

                    long totalPhotos = yearTotals.values().stream().mapToLong(Long::longValue).sum();
                    updateStatus("Timeline: " + totalPhotos + " photos across " + sortedYears.size() + " years");
                });
            } catch (Exception e) {
                logger.error("TimelinePanel", "Failed to load timeline", e);
                Platform.runLater(() -> {
                    contentBox.getChildren().clear();
                    Label error = new Label("Failed to load timeline: " + e.getMessage());
                    error.setStyle("-fx-text-fill: red;");
                    contentBox.getChildren().add(error);
                    updateStatus("Timeline error: " + e.getMessage());
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private VBox buildYearSection(String year, TreeMap<String, Long> months, long yearTotal, boolean expanded) {
        VBox section = new VBox(2);

        // Year header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 8, 10));
        header.setStyle("-fx-background-color: #2a2a2a; -fx-background-radius: 4;");
        header.setCursor(Cursor.HAND);

        Label arrow = new Label(expanded ? "\u25BC" : "\u25B6");
        arrow.setStyle("-fx-text-fill: #ccc; -fx-font-size: 12;");

        Label yearLabel = new Label(year);
        yearLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        yearLabel.setStyle("-fx-text-fill: #eee;");

        Label countLabel = new Label("(" + yearTotal + " photos)");
        countLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 13;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Separator sep = new Separator();
        sep.setPrefWidth(100);
        HBox.setHgrow(sep, Priority.ALWAYS);

        header.getChildren().addAll(arrow, yearLabel, countLabel, spacer);

        // Content area for months
        VBox monthsBox = new VBox(2);
        monthsBox.setPadding(new Insets(0, 0, 10, 10));
        yearContentMap.put(year, monthsBox);

        if (expanded) {
            buildMonthSections(monthsBox, months);
            monthsBox.setVisible(true);
            monthsBox.setManaged(true);
        } else {
            monthsBox.setVisible(false);
            monthsBox.setManaged(false);
        }

        // Click handler for expand/collapse
        header.setOnMouseClicked(e -> {
            boolean isExpanded = expandedYears.contains(year);
            if (isExpanded) {
                expandedYears.remove(year);
                arrow.setText("\u25B6");
                monthsBox.setVisible(false);
                monthsBox.setManaged(false);
                // Clear children to free memory
                monthsBox.getChildren().clear();
            } else {
                expandedYears.add(year);
                arrow.setText("\u25BC");
                buildMonthSections(monthsBox, months);
                monthsBox.setVisible(true);
                monthsBox.setManaged(true);
            }
        });

        section.getChildren().addAll(header, monthsBox);
        return section;
    }

    private void buildMonthSections(VBox monthsBox, TreeMap<String, Long> months) {
        monthsBox.getChildren().clear();
        for (Map.Entry<String, Long> entry : months.entrySet()) {
            monthsBox.getChildren().add(buildMonthSection(entry.getKey(), entry.getValue()));
        }
    }

    private VBox buildMonthSection(String monthKey, long count) {
        VBox section = new VBox(4);
        section.setPadding(new Insets(4, 0, 8, 0));

        // Parse month name from key (e.g., "2024-03-01T00:00:00.000Z")
        String monthName;
        String year;
        String fromDate;
        String toDate;
        try {
            year = monthKey.substring(0, 4);
            int monthNum = Integer.parseInt(monthKey.substring(5, 7));
            java.time.Month month = java.time.Month.of(monthNum);
            monthName = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year;
            YearMonth ym = YearMonth.of(Integer.parseInt(year), monthNum);
            fromDate = ym.atDay(1).toString();
            toDate = ym.atEndOfMonth().toString();
        } catch (Exception e) {
            monthName = monthKey;
            fromDate = monthKey;
            toDate = monthKey;
        }

        // Month header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 4, 8));

        Label monthLabel = new Label(monthName);
        monthLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 14));
        monthLabel.setStyle("-fx-text-fill: #ddd;");

        Label monthCount = new Label(String.valueOf(count));
        monthCount.setStyle("-fx-text-fill: #888; -fx-font-size: 12;");

        Separator sep = new Separator();
        HBox.setHgrow(sep, Priority.ALWAYS);

        String finalFromDate = fromDate;
        String finalToDate = toDate;

        // Thumbnail grid
        FlowPane thumbnailGrid = new FlowPane(4, 4);
        thumbnailGrid.setPadding(new Insets(4, 0, 0, 0));
        Button slideshowBtn = new Button("\u25B6 Slideshow");
        slideshowBtn.setStyle("-fx-font-size: 11;");
        slideshowBtn.setOnAction(e -> launchSlideshow(finalFromDate, finalToDate, count));

        header.getChildren().addAll(monthLabel, sep, monthCount, slideshowBtn);

        section.getChildren().addAll(header, thumbnailGrid);

        // Load initial batch of thumbnails
        loadThumbnails(thumbnailGrid, section, finalFromDate, finalToDate, 0, INITIAL_BATCH, count);

        return section;
    }

    private void loadThumbnails(FlowPane grid, VBox section, String fromDate, String toDate,
                                int from, int size, long totalCount) {
        Thread thread = new Thread(() -> {
            try {
                List<ImageMetadata> images = openSearchService.searchByDateRange(fromDate, toDate, from, size);

                Platform.runLater(() -> {
                    int fitSize = Math.max(60, thumbnailSize / 2);

                    for (ImageMetadata image : images) {
                        ImageView imageView = new ImageView();
                        imageView.setFitWidth(fitSize);
                        imageView.setFitHeight(fitSize);
                        imageView.setPreserveRatio(true);
                        imageView.setCursor(Cursor.HAND);

                        // Wrap in a StackPane for consistent sizing and background
                        StackPane thumb = new StackPane(imageView);
                        thumb.setPrefSize(fitSize + 4, fitSize + 4);
                        thumb.setMinSize(fitSize + 4, fitSize + 4);
                        thumb.setMaxSize(fitSize + 4, fitSize + 4);
                        thumb.setStyle("-fx-background-color: #1a1a1a; -fx-background-radius: 2;");
                        thumb.setAlignment(Pos.CENTER);

                        // Single click to show in detail panel, double click to open in viewer
                        thumb.setOnMouseClicked(e -> {
                            if (e.getButton() == MouseButton.PRIMARY) {
                                if (e.getClickCount() == 2) {
                                    openInSystemViewer(image.getFilePath());
                                } else {
                                    detailPanel.showMetadata(image);
                                    updateStatus("Selected: " + image.getFileName());
                                }
                            }
                        });

                        // Tooltip with filename
                        Tooltip.install(thumb, new Tooltip(image.getFileName()));

                        // Load thumbnail async
                        String filePath = image.getFilePath();
                        thumbnailService.getThumbnailAsync(filePath, (path, thumbnail) ->
                                Platform.runLater(() -> imageView.setImage(thumbnail)));

                        grid.getChildren().add(thumb);
                    }

                    // Add "Show more" link if there are more images
                    int loaded = from + images.size();
                    if (loaded < totalCount) {
                        // Remove previous "show more" if exists
                        section.getChildren().removeIf(node ->
                                node instanceof Hyperlink && "show-more".equals(node.getId()));

                        Hyperlink showMore = new Hyperlink("\u25BC Show more (" + loaded + " of " + totalCount + ")");
                        showMore.setId("show-more");
                        showMore.setStyle("-fx-text-fill: #6699cc; -fx-font-size: 12;");
                        showMore.setOnAction(e -> {
                            section.getChildren().remove(showMore);
                            loadThumbnails(grid, section, fromDate, toDate, loaded, LOAD_MORE_BATCH, totalCount);
                        });
                        section.getChildren().add(showMore);
                    }
                });
            } catch (Exception e) {
                logger.error("TimelinePanel", "Failed to load thumbnails for " + fromDate, e);
                Platform.runLater(() -> {
                    Label error = new Label("Failed to load images");
                    error.setStyle("-fx-text-fill: red; -fx-font-size: 11;");
                    grid.getChildren().add(error);
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void launchSlideshow(String fromDate, String toDate, long totalCount) {
        updateStatus("Loading all images for slideshow...");

        Thread thread = new Thread(() -> {
            try {
                // Fetch all images for this month, not just the loaded thumbnails
                int batchSize = (int) Math.min(totalCount, 10000);
                List<ImageMetadata> allImages = openSearchService.searchByDateRange(fromDate, toDate, 0, batchSize);

                Platform.runLater(() -> {
                    if (allImages.isEmpty()) {
                        updateStatus("No images found for slideshow");
                        return;
                    }

                    BiConsumer<ImageMetadata, String> ratingCallback = (metadata, rating) -> {
                        Platform.runLater(() -> detailPanel.showMetadata(metadata));
                    };

                    SlideshowStage slideshow = new SlideshowStage(allImages, 0, ratingCallback);
                    slideshow.show();
                    updateStatus("Slideshow: " + allImages.size() + " images");
                });
            } catch (Exception e) {
                logger.error("TimelinePanel", "Failed to load images for slideshow", e);
                Platform.runLater(() -> updateStatus("Failed to load slideshow: " + e.getMessage()));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void openInSystemViewer(String filePath) {
        new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(new File(filePath));
            } catch (Exception e) {
                logger.error("TimelinePanel", "Failed to open file: " + filePath, e);
            }
        }).start();
    }

    private void updateStatus(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }
}
