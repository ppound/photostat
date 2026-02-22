package com.photostat.ui;

import com.photostat.models.FaceCluster;
import com.photostat.models.FaceDetection;
import com.photostat.services.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Panel for face detection, clustering, and person naming.
 */
public class FacesPanel extends BorderPane {

    private final FaceRecognitionService faceService;
    private final ThumbnailService thumbnailService;
    private final OpenSearchService openSearchService;
    private final ConfigService configService;

    private Button scanButton;
    private ProgressIndicator progressIndicator;
    private Label pythonStatusLabel;
    private Label summaryLabel;
    private Label currentFileLabel;
    private Label resaveHintLabel;
    private ListView<FaceCluster> clusterListView;
    private VBox detailBox;

    // Cluster list pagination
    private static final int CLUSTERS_PAGE_SIZE = 50;
    private static final int FACES_PAGE_SIZE = 20;
    private List<FaceCluster> allClusters = new ArrayList<>();
    private int clustersLoaded = 0;
    private Button loadMoreClustersButton;
    private Label clusterCountLabel;

    public FacesPanel() {
        this.faceService = FaceRecognitionService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.configService = ConfigService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setPadding(new Insets(20));

        // Top toolbar
        HBox toolbar = createToolbar();

        // Summary label
        summaryLabel = new Label("Click 'Scan for Faces' to detect faces in your indexed images.");
        summaryLabel.setStyle("-fx-font-size: 14px;");
        summaryLabel.setPadding(new Insets(10, 0, 10, 0));

        // Current file being processed — shown only during scanning
        currentFileLabel = new Label();
        currentFileLabel.setStyle("-fx-font-size: 11px;");
        currentFileLabel.getStyleClass().add("text-muted");
        currentFileLabel.setVisible(false);
        currentFileLabel.setManaged(false);

        // Warning shown after a scan if named clusters exist — they may have acquired new photos
        resaveHintLabel = new Label(
                "\u26a0  Named clusters may contain new photos from this scan. " +
                "Open each named cluster and click \u2018Save Name\u2019 to update the search index with the new images.");
        resaveHintLabel.setWrapText(true);
        resaveHintLabel.getStyleClass().add("warning-banner");
        resaveHintLabel.setVisible(false);
        resaveHintLabel.setManaged(false);

        // Main content: split between cluster list and detail
        SplitPane splitPane = new SplitPane();

        // Left: cluster list with pagination
        clusterListView = new ListView<>();
        clusterListView.setCellFactory(lv -> new FaceClusterCell());
        clusterListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            showClusterDetail(newVal);
        });

        loadMoreClustersButton = new Button("Load More Clusters");
        loadMoreClustersButton.setOnAction(e -> loadMoreClusters());
        loadMoreClustersButton.setMaxWidth(Double.MAX_VALUE);
        loadMoreClustersButton.setVisible(false);

        clusterCountLabel = new Label();
        clusterCountLabel.getStyleClass().add("text-muted");
        clusterCountLabel.setStyle("-fx-font-size: 11px;");
        clusterCountLabel.setVisible(false);

        VBox leftBox = new VBox(5);
        Label clustersLabel = new Label("Face Clusters");
        clustersLabel.setStyle("-fx-font-weight: bold;");
        leftBox.getChildren().addAll(clustersLabel, clusterListView, clusterCountLabel, loadMoreClustersButton);
        VBox.setVgrow(clusterListView, Priority.ALWAYS);

        // Right: selected cluster detail
        detailBox = new VBox(10);
        detailBox.setPadding(new Insets(10));
        ScrollPane detailScroll = new ScrollPane(detailBox);
        detailScroll.setFitToWidth(true);

        splitPane.getItems().addAll(leftBox, detailScroll);
        splitPane.setDividerPositions(0.35);

        VBox mainContent = new VBox(5, toolbar, summaryLabel, currentFileLabel, resaveHintLabel, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        setCenter(mainContent);

        // Update initial summary
        updateSummary();
    }

    private HBox createToolbar() {
        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 10, 0));

        scanButton = new Button("Scan for Faces");
        scanButton.setStyle("-fx-font-weight: bold;");
        scanButton.setOnAction(e -> scanForFaces());

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(24, 24);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label pythonLabel = new Label("Python:");
        pythonStatusLabel = new Label("Checking...");
        pythonStatusLabel.getStyleClass().add("text-muted");

        toolbar.getChildren().addAll(scanButton, progressIndicator, spacer, pythonLabel, pythonStatusLabel);

        // Check Python status in background
        checkPythonStatus();

        return toolbar;
    }

    private void checkPythonStatus() {
        Thread thread = new Thread(() -> {
            boolean available = faceService.isPythonAvailable();
            String versionInfo = available ? faceService.getPythonVersionInfo() : "";
            boolean gpuAvailable = versionInfo.contains("\"gpu_available\": true");
            // gpu_error is set when CUDA was attempted but the provider DLL failed to load
            String gpuError = extractJsonString(versionInfo, "gpu_error");
            Platform.runLater(() -> {
                if (available) {
                    String statusText = gpuAvailable ? "Available (GPU)" : "Available (CPU)";
                    pythonStatusLabel.setText(statusText);
                    pythonStatusLabel.getStyleClass().removeAll("text-muted", "text-error");
                    pythonStatusLabel.getStyleClass().add("text-success");
                    if (gpuError != null) {
                        // GPU was attempted but failed — show the reason as a tooltip
                        Tooltip tip = new Tooltip(
                                "GPU unavailable: " + gpuError + "\n\nSee docs/FACE_RECOGNITION.md for setup steps.");
                        tip.setWrapText(true);
                        tip.setMaxWidth(420);
                        pythonStatusLabel.setTooltip(tip);
                    }
                } else {
                    pythonStatusLabel.setText("Not found");
                    pythonStatusLabel.getStyleClass().removeAll("text-muted", "text-success");
                    pythonStatusLabel.getStyleClass().add("text-error");
                    scanButton.setDisable(true);
                }
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Extract a string value from a JSON string by key, without a full JSON parser.
     * Returns null if the key is not present or the value cannot be extracted.
     */
    private String extractJsonString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        try {
            int valueStart = idx + search.length();
            // Skip whitespace
            while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
            if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;
            valueStart++; // skip opening quote
            // Read until unescaped closing quote
            StringBuilder sb = new StringBuilder();
            for (int i = valueStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    i++; // skip escaped character
                    sb.append(json.charAt(i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Load clusters into the list, sorted by size (largest first), paginated.
     */
    private void loadClusterList(List<FaceCluster> clusters) {
        // Sort by face count descending so the most important clusters appear first
        allClusters = new ArrayList<>(clusters);
        allClusters.sort(Comparator.comparingInt((FaceCluster c) -> c.getFaceIds().size()).reversed());

        clustersLoaded = 0;
        clusterListView.getItems().clear();
        clearDetailImages();
        loadMoreClusters();
    }

    private void loadMoreClusters() {
        int nextBatch = Math.min(clustersLoaded + CLUSTERS_PAGE_SIZE, allClusters.size());
        List<FaceCluster> page = allClusters.subList(clustersLoaded, nextBatch);

        // Resolve faces only for this page
        faceService.resolveClustersPage(page);

        clusterListView.getItems().addAll(page);
        clustersLoaded = nextBatch;

        boolean hasMore = clustersLoaded < allClusters.size();
        loadMoreClustersButton.setVisible(hasMore);
        clusterCountLabel.setVisible(true);
        clusterCountLabel.setText("Showing " + clustersLoaded + " of " + allClusters.size() + " clusters");
        if (!hasMore) {
            loadMoreClustersButton.setText("All clusters loaded");
            loadMoreClustersButton.setDisable(true);
        } else {
            loadMoreClustersButton.setText("Load More Clusters");
            loadMoreClustersButton.setDisable(false);
        }
    }

    private void scanForFaces() {
        if (!configService.isFacesEnabled()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Face Recognition Disabled");
            alert.setHeaderText(null);
            alert.setContentText("Face recognition is disabled. Enable it in Settings > Face Recognition.");
            alert.show();
            return;
        }

        if (!openSearchService.isConnected()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Not Connected");
            alert.setHeaderText(null);
            alert.setContentText("Please connect to OpenSearch first and index some images.");
            alert.show();
            return;
        }

        scanButton.setDisable(true);
        progressIndicator.setVisible(true);
        summaryLabel.setText("Fetching image paths...");
        currentFileLabel.setText("");
        currentFileLabel.setVisible(true);
        currentFileLabel.setManaged(true);
        resaveHintLabel.setVisible(false);
        resaveHintLabel.setManaged(false);
        clusterListView.getItems().clear();
        clearDetailImages();

        Task<List<FaceCluster>> task = new Task<>() {
            @Override
            protected List<FaceCluster> call() throws Exception {
                // Fetch all file paths from OpenSearch
                List<String> allPaths = openSearchService.searchAllFilePaths(1000);

                // Filter to common image types (skip RAW for speed)
                List<String> imagePaths = allPaths.stream()
                        .filter(p -> {
                            String lower = p.toLowerCase();
                            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
                        })
                        .collect(Collectors.toList());

                // Show incremental scan info
                List<String> newPaths = faceService.filterNewPaths(imagePaths);
                int skipped = imagePaths.size() - newPaths.size();
                Platform.runLater(() ->
                        summaryLabel.setText("Detecting faces in " + newPaths.size() + " new images" +
                                (skipped > 0 ? " (" + skipped + " already scanned)" : "") + "..."));

                // Detect faces (incremental — skips already-scanned images)
                faceService.detectFacesBatch(imagePaths, progress -> {
                    Platform.runLater(() -> {
                        int current = faceService.getCurrentScanCount();
                        int total = faceService.getTotalScanCount();
                        String countStr = total > 0 ? current + " / " + total : "";
                        summaryLabel.setText("Detecting faces... " + (int)(progress * 100) + "%" +
                                (countStr.isEmpty() ? "" : "  (" + countStr + " images)"));
                        progressIndicator.setProgress(progress);
                        String file = faceService.getCurrentScanFile();
                        if (file != null) {
                            int sep = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                            currentFileLabel.setText("Processing: " + file.substring(sep + 1));
                        }
                    });
                });

                Platform.runLater(() -> summaryLabel.setText("Clustering faces..."));

                // Cluster faces
                return faceService.clusterFaces();
            }
        };

        task.setOnSucceeded(e -> {
            List<FaceCluster> result = task.getValue();
            loadClusterList(result);
            updateSummary();
            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
            progressIndicator.setProgress(-1);
            currentFileLabel.setVisible(false);
            currentFileLabel.setManaged(false);

            // Warn if any clusters are already named — their new photos won't be in the
            // search index until the user clicks Save Name again on each one.
            long namedClusters = result.stream()
                    .filter(c -> c.getPersonName() != null && !c.getPersonName().isEmpty())
                    .count();
            if (namedClusters > 0) {
                resaveHintLabel.setVisible(true);
                resaveHintLabel.setManaged(true);
            }
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            summaryLabel.setText("Scan failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
            progressIndicator.setProgress(-1);
            currentFileLabel.setVisible(false);
            currentFileLabel.setManaged(false);
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showClusterDetail(FaceCluster cluster) {
        // Release Image references from previous detail to help GC
        clearDetailImages();

        if (cluster == null) return;

        // Name editing
        Label nameLabel = new Label("Person Name:");
        nameLabel.setStyle("-fx-font-weight: bold;");

        TextField nameField = new TextField(cluster.getPersonName() != null ? cluster.getPersonName() : "");
        nameField.setPromptText("Enter person name");
        nameField.setPrefWidth(200);

        Button saveNameButton = new Button("Save Name");
        saveNameButton.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (!name.isEmpty()) {
                saveName(cluster, name);
            }
        });

        HBox nameBox = new HBox(10, nameLabel, nameField, saveNameButton);
        nameBox.setAlignment(Pos.CENTER_LEFT);

        // Merge button
        Button mergeButton = new Button("Merge with...");
        mergeButton.setOnAction(e -> showMergeDialog(cluster));

        // Info
        Label infoLabel = new Label(
                cluster.getFaceIds().size() + " faces, " + cluster.getPhotoCount() + " photos"
        );
        infoLabel.getStyleClass().add("text-muted");

        detailBox.getChildren().addAll(nameBox, mergeButton, infoLabel, new Separator());

        // Face thumbnails grid with pagination
        FlowPane facesGrid = new FlowPane(10, 10);
        facesGrid.setPadding(new Insets(10, 0, 0, 0));

        List<FaceDetection> allFaces = cluster.getFaces();
        int totalFaces = allFaces.size();
        int initialLoad = Math.min(FACES_PAGE_SIZE, totalFaces);

        for (int i = 0; i < initialLoad; i++) {
            facesGrid.getChildren().add(createFaceThumbnail(allFaces.get(i)));
        }

        detailBox.getChildren().add(facesGrid);

        // "Load More" button if there are more faces
        if (totalFaces > FACES_PAGE_SIZE) {
            int[] loaded = {initialLoad};
            Label countLabel = new Label("Showing " + initialLoad + " of " + totalFaces + " faces");
            countLabel.getStyleClass().add("text-muted");

            Button loadMoreButton = new Button("Load More");
            loadMoreButton.setOnAction(e -> {
                int nextBatch = Math.min(loaded[0] + FACES_PAGE_SIZE, totalFaces);
                for (int i = loaded[0]; i < nextBatch; i++) {
                    facesGrid.getChildren().add(createFaceThumbnail(allFaces.get(i)));
                }
                loaded[0] = nextBatch;
                countLabel.setText("Showing " + loaded[0] + " of " + totalFaces + " faces");
                if (loaded[0] >= totalFaces) {
                    loadMoreButton.setDisable(true);
                    loadMoreButton.setText("All faces loaded");
                }
            });

            HBox loadMoreBox = new HBox(10, loadMoreButton, countLabel);
            loadMoreBox.setAlignment(Pos.CENTER_LEFT);
            loadMoreBox.setPadding(new Insets(10, 0, 0, 0));
            detailBox.getChildren().add(loadMoreBox);
        }
    }

    private VBox createFaceThumbnail(FaceDetection face) {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(130);

        ImageView imageView = new ImageView();
        imageView.setFitWidth(120);
        imageView.setFitHeight(120);
        imageView.setPreserveRatio(true);

        // Load face crop async
        thumbnailService.getFaceCropAsync(
                face.getImagePath(), face.getX(), face.getY(), face.getWidth(), face.getHeight(),
                (path, image) -> Platform.runLater(() -> {
                    if (image != null) {
                        imageView.setImage(image);
                    }
                })
        );

        // File name label
        String fileName = face.getImagePath();
        int lastSep = fileName.lastIndexOf('/');
        if (lastSep < 0) lastSep = fileName.lastIndexOf('\\');
        if (lastSep >= 0) fileName = fileName.substring(lastSep + 1);
        if (fileName.length() > 18) fileName = fileName.substring(0, 15) + "...";

        Label fileLabel = new Label(fileName);
        fileLabel.setStyle("-fx-font-size: 10px;");

        Label confLabel = new Label(String.format("%.0f%%", face.getConfidence() * 100));
        confLabel.getStyleClass().add("text-muted");
        confLabel.setStyle("-fx-font-size: 10px;");

        box.getChildren().addAll(imageView, fileLabel, confLabel);

        // Click to open source image
        box.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().open(new java.io.File(face.getImagePath()));
            } catch (Exception ex) {
                // Ignore
            }
        });
        box.setStyle("-fx-cursor: hand;");

        return box;
    }

    private void saveName(FaceCluster cluster, String name) {
        // Disable UI to prevent concurrent saves
        clusterListView.setDisable(true);
        scanButton.setDisable(true);

        // Add progress bar to detail box
        ProgressBar saveProgressBar = new ProgressBar(0);
        saveProgressBar.setPrefWidth(300);
        Label saveProgressLabel = new Label("Saving name '" + name + "' — 0 / ? images...");
        saveProgressLabel.getStyleClass().add("text-muted");
        HBox progressBox = new HBox(10, saveProgressBar, saveProgressLabel);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        progressBox.setPadding(new Insets(5, 0, 5, 0));
        // Insert after the separator (index 3)
        int insertIndex = Math.min(3, detailBox.getChildren().size());
        detailBox.getChildren().add(insertIndex, progressBox);

        summaryLabel.setText("Saving name '" + name + "'...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                faceService.assignName(cluster.getClusterId(), name, progress -> {
                    int current = (int) progress[0];
                    int total = (int) progress[1];
                    Platform.runLater(() -> {
                        saveProgressBar.setProgress((double) current / total);
                        saveProgressLabel.setText("Saving name '" + name + "' — " + current + " / " + total + " images...");
                    });
                });
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            detailBox.getChildren().remove(progressBox);
            clusterListView.setDisable(false);
            scanButton.setDisable(false);
            updateSummary();
            clusterListView.refresh();
        });

        task.setOnFailed(e -> {
            detailBox.getChildren().remove(progressBox);
            clusterListView.setDisable(false);
            scanButton.setDisable(false);
            Throwable ex = task.getException();
            summaryLabel.setText("Failed to save name: " + (ex != null ? ex.getMessage() : "Unknown error"));
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showMergeDialog(FaceCluster target) {
        List<FaceCluster> others = clusterListView.getItems().stream()
                .filter(c -> !c.getClusterId().equals(target.getClusterId()))
                .collect(Collectors.toList());

        if (others.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Merge");
            alert.setHeaderText(null);
            alert.setContentText("No other clusters to merge with.");
            alert.show();
            return;
        }

        ChoiceDialog<FaceCluster> dialog = new ChoiceDialog<>(others.get(0), others);
        dialog.setTitle("Merge Clusters");
        dialog.setHeaderText("Merge into: " + target.getDisplayName());
        dialog.setContentText("Select cluster to merge:");

        dialog.showAndWait().ifPresent(source -> {
            List<String> sourceIds = new ArrayList<>();
            sourceIds.add(source.getClusterId());
            faceService.mergeClusters(target.getClusterId(), sourceIds);

            // Reload with pagination
            loadClusterList(faceService.getClusters());
            updateSummary();
        });
    }

    private void updateSummary() {
        int totalFaces = faceService.getFaceDetections().size();
        int totalClusters = faceService.getClusters().size();
        long namedClusters = faceService.getClusters().stream()
                .filter(c -> c.getPersonName() != null && !c.getPersonName().isEmpty())
                .count();

        if (totalFaces == 0) {
            summaryLabel.setText("Click 'Scan for Faces' to detect faces in your indexed images.");
        } else {
            summaryLabel.setText(String.format("%d faces, %d clusters, %d named", totalFaces, totalClusters, namedClusters));
        }
    }

    public void refresh() {
        // Only do a full reload if the list is empty (first visit or after scan)
        // This prevents re-rendering all cluster cells and leaking Image objects on every tab switch
        if (clusterListView.getItems().isEmpty() && !faceService.getClusters().isEmpty()) {
            faceService.loadState();
            loadClusterList(faceService.getClusters());
        }
        updateSummary();
    }

    /**
     * Force a full reload of face data from disk.
     */
    public void forceRefresh() {
        faceService.loadState();
        clearDetailImages();
        loadClusterList(faceService.getClusters());
        updateSummary();
        checkPythonStatus();
    }

    /**
     * Clear Image references from the detail panel to allow garbage collection.
     */
    private void clearDetailImages() {
        for (javafx.scene.Node node : detailBox.getChildren()) {
            if (node instanceof FlowPane flowPane) {
                for (javafx.scene.Node child : flowPane.getChildren()) {
                    if (child instanceof VBox vbox) {
                        for (javafx.scene.Node inner : vbox.getChildren()) {
                            if (inner instanceof ImageView iv) {
                                iv.setImage(null);
                            }
                        }
                    }
                }
            }
        }
        detailBox.getChildren().clear();
    }

    /**
     * Custom ListCell for displaying face clusters.
     * Reuses layout nodes to avoid creating new objects on every updateItem call.
     */
    private class FaceClusterCell extends ListCell<FaceCluster> {
        private final HBox row;
        private final ImageView imageView;
        private final Label nameLabel;
        private final Label statsLabel;

        FaceClusterCell() {
            row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5));

            imageView = new ImageView();
            imageView.setFitWidth(48);
            imageView.setFitHeight(48);
            imageView.setPreserveRatio(true);

            VBox infoBox = new VBox(2);
            nameLabel = new Label();
            nameLabel.setStyle("-fx-font-weight: bold;");
            statsLabel = new Label();
            statsLabel.getStyleClass().add("text-muted");
            statsLabel.setStyle("-fx-font-size: 11px;");
            infoBox.getChildren().addAll(nameLabel, statsLabel);

            row.getChildren().addAll(imageView, infoBox);
        }

        @Override
        protected void updateItem(FaceCluster cluster, boolean empty) {
            super.updateItem(cluster, empty);

            if (empty || cluster == null) {
                setText(null);
                setGraphic(null);
                imageView.setImage(null);
                return;
            }

            nameLabel.setText(cluster.getDisplayName());
            statsLabel.setText(cluster.getFaceIds().size() + " faces, " +
                    cluster.getPhotoCount() + " photos");

            imageView.setImage(null);
            if (cluster.getRepresentativeFaceId() != null) {
                FaceDetection repFace = faceService.getFaceById(cluster.getRepresentativeFaceId());
                if (repFace != null) {
                    thumbnailService.getFaceCropAsync(
                            repFace.getImagePath(), repFace.getX(), repFace.getY(),
                            repFace.getWidth(), repFace.getHeight(),
                            (path, image) -> Platform.runLater(() -> {
                                if (image != null && cluster.equals(getItem())) {
                                    imageView.setImage(image);
                                }
                            })
                    );
                }
            }

            setGraphic(row);
        }
    }
}
