package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
    private ChartsPanel chartsPanel;

    private Label statusLabel;
    private Label connectionLabel;

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

        // Create Charts tab
        Tab chartsTab = new Tab("Charts");
        chartsPanel = new ChartsPanel();
        chartsTab.setContent(chartsPanel);

        tabPane.getTabs().addAll(searchTab, indexTab, chartsTab);

        // Refresh charts when switching to charts tab
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == chartsTab) {
                chartsPanel.refresh();
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

        // Wire up detail panel metadata save to refresh table display
        // (just redraws the table - doesn't reload from OpenSearch since data is already updated in memory)
        detailPanel.setMetadataSavedCallback(() -> {
            resultsPanel.refreshTableDisplay();
        });

        // Wire up detail panel status updates to status bar
        detailPanel.setStatusCallback(this::updateStatus);

        // Wire up results panel status updates to status bar
        resultsPanel.setStatusCallback(this::updateStatus);

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

        Button clearFiltersButton = new Button("Clear Filters");
        clearFiltersButton.setOnAction(e -> {
            if (searchPanel != null) {
                searchPanel.clearFilters();
            }
        });

        toolbar.getItems().addAll(
                settingsButton,
                new Separator(),
                refreshButton,
                clearFiltersButton
        );

        return toolbar;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(20);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("Ready");
        connectionLabel = new Label("Disconnected");
        connectionLabel.setStyle("-fx-text-fill: #cc0000;");

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
                        connectionLabel.setStyle("-fx-text-fill: #00aa00;");
                        updateStatus("Connected to OpenSearch");

                        // Initial search
                        if (searchPanel != null) {
                            searchPanel.executeSearch();
                        }
                    });
                } else {
                    Platform.runLater(() -> {
                        connectionLabel.setText("Connection failed");
                        connectionLabel.setStyle("-fx-text-fill: #cc0000;");
                        updateStatus("Failed to connect to OpenSearch");
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    connectionLabel.setText("Error: " + e.getMessage());
                    connectionLabel.setStyle("-fx-text-fill: #cc0000;");
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

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void updateConnectionStatus(String message, boolean connected) {
        Platform.runLater(() -> {
            connectionLabel.setText(message);
            connectionLabel.setStyle(connected ?
                    "-fx-text-fill: #00aa00;" : "-fx-text-fill: #cc0000;");
        });
    }
}
