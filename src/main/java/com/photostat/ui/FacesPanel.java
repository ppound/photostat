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
    private ListView<FaceCluster> clusterListView;
    private VBox detailBox;

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

        // Main content: split between cluster list and detail
        SplitPane splitPane = new SplitPane();

        // Left: cluster list
        clusterListView = new ListView<>();
        clusterListView.setCellFactory(lv -> new FaceClusterCell());
        clusterListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            showClusterDetail(newVal);
        });

        VBox leftBox = new VBox(5);
        Label clustersLabel = new Label("Face Clusters");
        clustersLabel.setStyle("-fx-font-weight: bold;");
        leftBox.getChildren().addAll(clustersLabel, clusterListView);
        VBox.setVgrow(clusterListView, Priority.ALWAYS);

        // Right: selected cluster detail
        detailBox = new VBox(10);
        detailBox.setPadding(new Insets(10));
        ScrollPane detailScroll = new ScrollPane(detailBox);
        detailScroll.setFitToWidth(true);

        splitPane.getItems().addAll(leftBox, detailScroll);
        splitPane.setDividerPositions(0.35);

        VBox mainContent = new VBox(5, toolbar, summaryLabel, splitPane);
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
            Platform.runLater(() -> {
                if (available) {
                    String statusText = gpuAvailable ? "Available (GPU)" : "Available (CPU)";
                    pythonStatusLabel.setText(statusText);
                    pythonStatusLabel.getStyleClass().removeAll("text-muted", "text-error");
                    pythonStatusLabel.getStyleClass().add("text-success");
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
        clusterListView.getItems().clear();
        detailBox.getChildren().clear();

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
                        int pct = (int) (progress * 100);
                        summaryLabel.setText("Detecting faces... " + pct + "%");
                        progressIndicator.setProgress(progress);
                    });
                });

                Platform.runLater(() -> summaryLabel.setText("Clustering faces..."));

                // Cluster faces
                return faceService.clusterFaces();
            }
        };

        task.setOnSucceeded(e -> {
            List<FaceCluster> result = task.getValue();
            clusterListView.getItems().setAll(result);
            updateSummary();
            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
            progressIndicator.setProgress(-1);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            summaryLabel.setText("Scan failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
            progressIndicator.setProgress(-1);
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showClusterDetail(FaceCluster cluster) {
        detailBox.getChildren().clear();

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

        // Face thumbnails grid
        FlowPane facesGrid = new FlowPane(10, 10);
        facesGrid.setPadding(new Insets(10, 0, 0, 0));

        for (FaceDetection face : cluster.getFaces()) {
            VBox faceBox = createFaceThumbnail(face);
            facesGrid.getChildren().add(faceBox);
        }

        detailBox.getChildren().add(facesGrid);
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
        summaryLabel.setText("Saving name '" + name + "'...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                faceService.assignName(cluster.getClusterId(), name);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            updateSummary();
            // Refresh the list view to show updated name
            clusterListView.refresh();
        });

        task.setOnFailed(e -> {
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

            // Reload
            clusterListView.getItems().setAll(faceService.getClusters());
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
        faceService.loadState();
        clusterListView.getItems().setAll(faceService.getClusters());
        updateSummary();
        checkPythonStatus();
    }

    /**
     * Custom ListCell for displaying face clusters.
     */
    private class FaceClusterCell extends ListCell<FaceCluster> {
        @Override
        protected void updateItem(FaceCluster cluster, boolean empty) {
            super.updateItem(cluster, empty);

            if (empty || cluster == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5));

            // Representative face thumbnail
            ImageView imageView = new ImageView();
            imageView.setFitWidth(48);
            imageView.setFitHeight(48);
            imageView.setPreserveRatio(true);

            if (cluster.getRepresentativeFaceId() != null) {
                FaceDetection repFace = faceService.getFaceById(cluster.getRepresentativeFaceId());
                if (repFace != null) {
                    thumbnailService.getFaceCropAsync(
                            repFace.getImagePath(), repFace.getX(), repFace.getY(),
                            repFace.getWidth(), repFace.getHeight(),
                            (path, image) -> Platform.runLater(() -> {
                                if (image != null) {
                                    imageView.setImage(image);
                                }
                            })
                    );
                }
            }

            // Name and info
            VBox infoBox = new VBox(2);
            Label nameLabel = new Label(cluster.getDisplayName());
            nameLabel.setStyle("-fx-font-weight: bold;");

            Label statsLabel = new Label(cluster.getFaceIds().size() + " faces, " +
                    cluster.getPhotoCount() + " photos");
            statsLabel.getStyleClass().add("text-muted");
            statsLabel.setStyle("-fx-font-size: 11px;");

            infoBox.getChildren().addAll(nameLabel, statsLabel);

            row.getChildren().addAll(imageView, infoBox);
            setGraphic(row);
        }
    }
}
