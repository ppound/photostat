package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.RcloneService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application window with tab-based navigation.
 */
public class MainWindow extends BorderPane {

    private final ConfigService configService;
    private final OpenSearchService openSearchService;

    private TabPane tabPane;
    private SearchPanel searchPanel;
    private ResultsPanel resultsPanel;
    private FacetsPanel facetsPanel;
    private DetailPanel detailPanel;
    private IndexPanel indexPanel;
    private DuplicatesPanel duplicatesPanel;
    private FacesPanel facesPanel;
    private MapPanel mapPanel;
    private TimelinePanel timelinePanel;
    private ChartsPanel chartsPanel;

    private Label statusLabel;
    private Label connectionLabel;
    private Button backButton;

    public MainWindow() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();

        initializeUI();
        connectToOpenSearch();
    }

    private void initializeUI() {
        // Create main tab pane
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Create Search tab
        Tab searchTab = new Tab("Search");
        searchTab.setContent(createSearchView());

        // Create Index tab
        Tab indexTab = new Tab("Index");
        indexPanel = new IndexPanel();
        indexTab.setContent(indexPanel);

        // Create Duplicates tab
        Tab duplicatesTab = new Tab("Duplicates");
        duplicatesPanel = new DuplicatesPanel();
        duplicatesTab.setContent(duplicatesPanel);

        // Create Faces tab
        Tab facesTab = new Tab("Faces");
        facesPanel = new FacesPanel();
        facesTab.setContent(facesPanel);

        // Create Map tab
        Tab mapTab = new Tab("Map");
        mapPanel = new MapPanel();
        mapTab.setContent(mapPanel);

        // Create Timeline tab
        Tab timelineTab = new Tab("Timeline");
        timelinePanel = new TimelinePanel();
        timelinePanel.setStatusCallback(this::updateStatus);
        timelineTab.setContent(timelinePanel);

        // Create Charts tab
        Tab chartsTab = new Tab("Charts");
        chartsPanel = new ChartsPanel();
        chartsTab.setContent(chartsPanel);

        tabPane.getTabs().addAll(searchTab, indexTab, duplicatesTab, facesTab, mapTab, timelineTab, chartsTab);

        // Refresh panels when switching tabs
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == chartsTab) {
                chartsPanel.refresh();
            } else if (newTab == timelineTab) {
                timelinePanel.refresh();
            } else if (newTab == mapTab) {
                mapPanel.refresh();
            } else if (newTab == facesTab) {
                facesPanel.refresh();
            }
        });

        setCenter(tabPane);

        // Create toolbar
        ToolBar toolbar = createToolbar();
        setTop(toolbar);

        // Create status bar
        HBox statusBar = createStatusBar();
        setBottom(statusBar);
    }

    private BorderPane createSearchView() {
        BorderPane searchView = new BorderPane();

        // Create panels
        searchPanel = new SearchPanel();
        resultsPanel = new ResultsPanel();
        facetsPanel = new FacetsPanel();
        detailPanel = new DetailPanel();

        // Wire up search panel to results panel
        searchPanel.setSearchCallback((query, filters) -> {
            resultsPanel.search(query, filters);
        });

        // Wire up results panel to facets and detail
        resultsPanel.setSelectionCallback(metadata -> {
            if (metadata != null) {
                detailPanel.showMetadata(metadata);
            }
        });

        resultsPanel.setAggregationsCallback(aggregations -> {
            facetsPanel.updateFacets(aggregations);
            searchPanel.updateFilterOptions(aggregations);
        });

        // Wire up facets to apply filters
        facetsPanel.setFilterCallback((field, value) -> {
            searchPanel.addFilter(field, value);
            searchPanel.executeSearch();
        });

        // Wire up result chip clicks to apply filters
        resultsPanel.setChipClickCallback((field, value) -> {
            searchPanel.addFilter(field, value);
            searchPanel.executeSearch();
        });

        // Wire up detail panel metadata save to refresh table display
        // (just redraws the table - doesn't reload from OpenSearch since data is already updated in memory)
        detailPanel.setMetadataSavedCallback(() -> {
            resultsPanel.refreshTableDisplay();
        });

        // Wire up detail panel status updates to status bar
        detailPanel.setStatusCallback(this::updateStatus);

        // Wire up results panel status updates to status bar
        resultsPanel.setStatusCallback(this::updateStatus);

        // Wire up keyboard rating changes to sync detail panel
        resultsPanel.setRatingChangedCallback(metadata -> detailPanel.updateRatingDisplay(metadata));

        // Wire up search history to Back button
        searchPanel.setHistoryChangedCallback(() ->
                backButton.setDisable(!searchPanel.hasHistory()));

        // Layout
        VBox leftSide = new VBox(10);
        leftSide.setPadding(new Insets(10));
        leftSide.getChildren().addAll(searchPanel, facetsPanel);
        VBox.setVgrow(facetsPanel, Priority.ALWAYS);
        leftSide.setMinWidth(250);
        leftSide.setPrefWidth(280);
        leftSide.setMaxWidth(400);

        VBox center = new VBox(10);
        center.setPadding(new Insets(10));
        center.getChildren().add(resultsPanel);
        VBox.setVgrow(resultsPanel, Priority.ALWAYS);

        detailPanel.setMinWidth(300);
        detailPanel.setPrefWidth(350);
        detailPanel.setMaxWidth(500);

        SplitPane mainSplit = new SplitPane();
        mainSplit.getItems().addAll(leftSide, center, detailPanel);
        mainSplit.setDividerPositions(0.2, 0.75);

        // Ensure SplitPane respects size constraints
        SplitPane.setResizableWithParent(leftSide, false);
        SplitPane.setResizableWithParent(detailPanel, false);

        searchView.setCenter(mainSplit);

        return searchView;
    }

    private ToolBar createToolbar() {
        ToolBar toolbar = new ToolBar();

        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> showSettingsDialog());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshCurrentView());

        backButton = new Button("Back");
        backButton.setDisable(true);
        backButton.setOnAction(e -> {
            if (searchPanel != null) {
                searchPanel.goBack();
            }
        });

        Button clearFiltersButton = new Button("Clear Filters");
        clearFiltersButton.setOnAction(e -> {
            if (searchPanel != null) {
                searchPanel.clearFilters();
            }
        });

        Button uploadButton = new Button("Upload to Cloud");
        uploadButton.setOnAction(e -> showUploadDialog());

        toolbar.getItems().addAll(
                settingsButton,
                new Separator(),
                refreshButton,
                backButton,
                clearFiltersButton,
                new Separator(),
                uploadButton
        );

        return toolbar;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(20);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.getStyleClass().add("status-bar");

        statusLabel = new Label("Ready");
        connectionLabel = new Label("Disconnected");
        connectionLabel.getStyleClass().add("text-error");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusLabel, spacer, connectionLabel);

        return statusBar;
    }

    private void connectToOpenSearch() {
        Thread thread = new Thread(() -> {
            try {
                updateStatus("Connecting to OpenSearch...");
                openSearchService.connect();

                if (openSearchService.testConnection()) {
                    openSearchService.createIndexIfNotExists();
                    long docCount = openSearchService.getDocumentCount();

                    Platform.runLater(() -> {
                        connectionLabel.setText("Connected (" + docCount + " documents)");
                        connectionLabel.getStyleClass().removeAll("text-error", "text-success");
                        connectionLabel.getStyleClass().add("text-success");
                        updateStatus("Connected to OpenSearch");

                        // Initial search
                        if (searchPanel != null) {
                            searchPanel.executeSearch();
                        }
                    });
                } else {
                    Platform.runLater(() -> {
                        connectionLabel.setText("Connection failed");
                        connectionLabel.getStyleClass().removeAll("text-error", "text-success");
                        connectionLabel.getStyleClass().add("text-error");
                        updateStatus("Failed to connect to OpenSearch");
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    connectionLabel.setText("Error: " + e.getMessage());
                    connectionLabel.getStyleClass().removeAll("text-error", "text-success");
                    connectionLabel.getStyleClass().add("text-error");
                    updateStatus("Connection error: " + e.getMessage());
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void showSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog();
        dialog.showAndWait().ifPresent(result -> {
            if (result) {
                // Settings were saved, reconnect
                connectToOpenSearch();
            }
        });
    }

    private void refreshCurrentView() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab.getText().equals("Search")) {
            if (searchPanel != null) {
                searchPanel.executeSearch();
            }
        } else if (selectedTab.getText().equals("Map")) {
            if (mapPanel != null) {
                mapPanel.refresh();
            }
        } else if (selectedTab.getText().equals("Timeline")) {
            if (timelinePanel != null) {
                timelinePanel.refresh();
            }
        } else if (selectedTab.getText().equals("Charts")) {
            if (chartsPanel != null) {
                chartsPanel.refresh();
            }
        }

        // Update document count
        Thread thread = new Thread(() -> {
            try {
                if (openSearchService.isConnected()) {
                    long docCount = openSearchService.getDocumentCount();
                    Platform.runLater(() -> {
                        connectionLabel.setText("Connected (" + docCount + " documents)");
                    });
                }
            } catch (Exception ignored) {}
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void showUploadDialog() {
        RcloneService rcloneService = RcloneService.getInstance();

        // Check if rclone is installed
        if (!rcloneService.isInstalled()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("rclone Not Found");
            alert.setHeaderText(null);
            alert.setContentText("rclone is not installed or not on PATH. Install it from https://rclone.org/downloads/ and configure the path in Settings > Cloud Upload.");
            alert.show();
            return;
        }

        // Check if configured
        if (!rcloneService.isConfigured()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Cloud Upload Not Configured");
            alert.setHeaderText(null);
            alert.setContentText("Please configure a remote name and upload directories in Settings > Cloud Upload.");
            alert.show();
            return;
        }

        String remoteName = configService.getRcloneRemoteName();
        String remotePath = configService.getRcloneRemotePath();
        List<String> dirs = configService.getRcloneUploadDirectories();

        // Create progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle("Upload to Cloud");
        progressDialog.setHeaderText("Uploading to " + remoteName + ":" + (remotePath.isEmpty() ? "" : remotePath));

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(500);

        Label statusLabel = new Label("Starting upload...");
        statusLabel.setPrefWidth(500);
        statusLabel.setWrapText(true);

        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPrefWidth(500);
        outputArea.setPrefHeight(200);
        outputArea.setWrapText(true);
        outputArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.getChildren().addAll(progressBar, statusLabel, outputArea);
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        progressDialog.getDialogPane().setContent(content);
        progressDialog.setResizable(true);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger fileCount = new java.util.concurrent.atomic.AtomicInteger(0);

        Task<List<RcloneService.UploadResult>> uploadTask = new Task<>() {
            @Override
            protected List<RcloneService.UploadResult> call() {
                int totalDirs = dirs.size();
                java.util.List<RcloneService.UploadResult> results = new java.util.ArrayList<>();

                for (int i = 0; i < totalDirs; i++) {
                    if (cancelled.get()) break;

                    String dir = dirs.get(i);
                    int dirIndex = i + 1;

                    Platform.runLater(() -> {
                        statusLabel.setText("[" + dirIndex + "/" + totalDirs + "] Uploading: " + dir);
                    });

                    RcloneService.UploadResult result = rcloneService.uploadDirectory(
                            dir, remoteName, remotePath, false, progress -> {
                                if (!cancelled.get() && progress.getStatusLine() != null && !progress.getStatusLine().isEmpty()) {
                                    String line = progress.getStatusLine();

                                    // Count copied files from rclone -v output
                                    // rclone logs "Copied (new)" or "Copied (replaced existing)" for each file
                                    String lineLower = line.toLowerCase();
                                    if (lineLower.contains(": copied")) {
                                        int count = fileCount.incrementAndGet();
                                        Platform.runLater(() -> {
                                            statusLabel.setText("[" + dirIndex + "/" + totalDirs + "] Uploading: " + dir + "  (" + count + " files transferred)");
                                            progressBar.setProgress(-1); // indeterminate
                                        });
                                    }

                                    // Filter out noisy stats lines, show transfer and error messages
                                    boolean isStatsLine = lineLower.startsWith("transferred:") ||
                                            lineLower.startsWith("elapsed time:") ||
                                            lineLower.startsWith("checks:") ||
                                            (lineLower.contains("%") && lineLower.contains("eta"));
                                    if (!isStatsLine) {
                                        Platform.runLater(() -> {
                                            outputArea.appendText(line + "\n");
                                        });
                                    }
                                }
                            });
                    results.add(result);
                }

                return results;
            }
        };

        progressDialog.setOnCloseRequest(event -> {
            cancelled.set(true);
            rcloneService.cancelUpload();
        });

        uploadTask.setOnSucceeded(event -> {
            List<RcloneService.UploadResult> results = uploadTask.getValue();
            long successCount = results.stream().filter(RcloneService.UploadResult::isSuccess).count();
            long failCount = results.size() - successCount;

            Platform.runLater(() -> {
                progressBar.setProgress(1.0);
                if (cancelled.get()) {
                    statusLabel.setText("Upload cancelled");
                } else {
                    statusLabel.setText("Upload complete: " + successCount + " succeeded, " + failCount + " failed");
                }

                // Show errors if any
                for (RcloneService.UploadResult r : results) {
                    if (!r.isSuccess()) {
                        outputArea.appendText("\n--- ERROR: " + r.getDirectory() + " ---\n");
                        if (r.getOutput() != null && !r.getOutput().isEmpty()) {
                            outputArea.appendText(r.getOutput());
                        }
                        if (r.getError() != null && !r.getError().isEmpty()) {
                            outputArea.appendText(r.getError() + "\n");
                        }
                    }
                }

                progressDialog.getDialogPane().getButtonTypes().clear();
                progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            });
        });

        uploadTask.setOnFailed(event -> {
            Throwable e = uploadTask.getException();
            Platform.runLater(() -> {
                statusLabel.setText("Error: " + (e != null ? e.getMessage() : "Unknown error"));
                if (e != null) {
                    outputArea.appendText("\n" + e.getMessage() + "\n");
                }
                progressDialog.getDialogPane().getButtonTypes().clear();
                progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            });
        });

        Thread thread = new Thread(uploadTask);
        thread.setDaemon(true);
        thread.start();

        progressDialog.showAndWait();
        cancelled.set(true);
        rcloneService.cancelUpload();
    }

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void updateConnectionStatus(String message, boolean connected) {
        Platform.runLater(() -> {
            connectionLabel.setText(message);
            connectionLabel.getStyleClass().removeAll("text-error", "text-success");
            connectionLabel.getStyleClass().add(connected ? "text-success" : "text-error");
        });
    }
}
