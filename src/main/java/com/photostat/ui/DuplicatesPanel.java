package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.OpenSearchService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for finding and managing duplicate images.
 */
public class DuplicatesPanel extends BorderPane {

    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;

    private RadioButton exactRadio;
    private RadioButton visualRadio;
    private Button scanButton;
    private ProgressIndicator progressIndicator;
    private Label summaryLabel;
    private ListView<OpenSearchService.DuplicateGroup> groupListView;
    private VBox detailBox;

    public DuplicatesPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setPadding(new Insets(20));

        // Top toolbar
        HBox toolbar = createToolbar();

        // Summary label
        summaryLabel = new Label("Click 'Scan for Duplicates' to search your indexed images.");
        summaryLabel.setStyle("-fx-font-size: 14px;");
        summaryLabel.setPadding(new Insets(10, 0, 10, 0));

        // Main content: split between group list and detail
        SplitPane splitPane = new SplitPane();

        // Left: duplicate group list
        groupListView = new ListView<>();
        groupListView.setCellFactory(lv -> new DuplicateGroupCell());
        groupListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            showGroupDetail(newVal);
        });

        VBox leftBox = new VBox(5);
        Label groupsLabel = new Label("Duplicate Groups");
        groupsLabel.setStyle("-fx-font-weight: bold;");
        leftBox.getChildren().addAll(groupsLabel, groupListView);
        VBox.setVgrow(groupListView, Priority.ALWAYS);

        // Right: selected group detail
        detailBox = new VBox(10);
        detailBox.setPadding(new Insets(10));
        ScrollPane detailScroll = new ScrollPane(detailBox);
        detailScroll.setFitToWidth(true);

        splitPane.getItems().addAll(leftBox, detailScroll);
        splitPane.setDividerPositions(0.4);

        VBox mainContent = new VBox(5, toolbar, summaryLabel, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        setCenter(mainContent);
    }

    private HBox createToolbar() {
        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 10, 0));

        Label modeLabel = new Label("Mode:");
        modeLabel.setStyle("-fx-font-weight: bold;");

        ToggleGroup modeGroup = new ToggleGroup();
        exactRadio = new RadioButton("Exact Duplicates");
        exactRadio.setToggleGroup(modeGroup);
        exactRadio.setSelected(true);
        exactRadio.setTooltip(new Tooltip("Find byte-for-byte identical files (SHA-256 hash)"));

        visualRadio = new RadioButton("Visual Duplicates");
        visualRadio.setToggleGroup(modeGroup);
        visualRadio.setTooltip(new Tooltip("Find visually similar images (perceptual hash — catches resized/recompressed copies)"));

        scanButton = new Button("Scan for Duplicates");
        scanButton.setStyle("-fx-font-weight: bold;");
        scanButton.setOnAction(e -> scanForDuplicates());

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(24, 24);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        toolbar.getChildren().addAll(modeLabel, exactRadio, visualRadio, spacer, scanButton, progressIndicator);

        return toolbar;
    }

    private void scanForDuplicates() {
        scanButton.setDisable(true);
        progressIndicator.setVisible(true);
        summaryLabel.setText("Scanning for duplicates...");
        groupListView.getItems().clear();
        detailBox.getChildren().clear();

        String hashField = exactRadio.isSelected() ? "content_hash" : "perceptual_hash";

        Task<List<OpenSearchService.DuplicateGroup>> task = new Task<>() {
            @Override
            protected List<OpenSearchService.DuplicateGroup> call() throws Exception {
                if (!openSearchService.isConnected()) {
                    openSearchService.connect();
                }
                return openSearchService.findDuplicates(hashField);
            }
        };

        task.setOnSucceeded(e -> {
            List<OpenSearchService.DuplicateGroup> groups = task.getValue();
            groupListView.getItems().setAll(groups);

            int totalImages = groups.stream().mapToInt(OpenSearchService.DuplicateGroup::getCount).sum();
            long reclaimableBytes = groups.stream().mapToLong(OpenSearchService.DuplicateGroup::getReclaimableSize).sum();
            String reclaimable = formatSize(reclaimableBytes);

            String mode = exactRadio.isSelected() ? "exact" : "visual";
            summaryLabel.setText(String.format("Found %d duplicate groups (%d images, %s reclaimable) — %s mode",
                    groups.size(), totalImages, reclaimable, mode));

            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            summaryLabel.setText("Scan failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            scanButton.setDisable(false);
            progressIndicator.setVisible(false);
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showGroupDetail(OpenSearchService.DuplicateGroup group) {
        detailBox.getChildren().clear();

        if (group == null) return;

        String hashLabel = exactRadio.isSelected() ? "Content Hash" : "Perceptual Hash";
        Label header = new Label(hashLabel + ": " + group.getHash());
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        header.setWrapText(true);

        Label countLabel = new Label(group.getCount() + " files, " +
                formatSize(group.getTotalSize()) + " total, " +
                formatSize(group.getReclaimableSize()) + " reclaimable");
        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        detailBox.getChildren().addAll(header, countLabel, new Separator());

        List<CheckBox> checkBoxes = new ArrayList<>();

        for (ImageMetadata img : group.getImages()) {
            HBox row = createImageRow(img, checkBoxes);
            detailBox.getChildren().add(row);
        }

        // Delete selected button
        Button deleteButton = new Button("Delete Selected Files");
        deleteButton.setStyle("-fx-text-fill: #cc0000;");
        deleteButton.setOnAction(e -> deleteSelected(group, checkBoxes));
        deleteButton.setDisable(true);

        // Enable delete button only when something is checked
        for (CheckBox cb : checkBoxes) {
            cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                long checkedCount = checkBoxes.stream().filter(CheckBox::isSelected).count();
                // Don't allow deleting all files in a group
                deleteButton.setDisable(checkedCount == 0 || checkedCount == checkBoxes.size());
            });
        }

        detailBox.getChildren().addAll(new Separator(), deleteButton);
    }

    private HBox createImageRow(ImageMetadata img, List<CheckBox> checkBoxes) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5));
        row.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");

        CheckBox checkBox = new CheckBox();
        checkBoxes.add(checkBox);

        ImageView imageView = new ImageView();
        imageView.setFitWidth(60);
        imageView.setFitHeight(60);
        imageView.setPreserveRatio(true);

        thumbnailService.getThumbnailAsync(img.getFilePath(), (path, thumbnail) -> {
            Platform.runLater(() -> imageView.setImage(thumbnail));
        });

        VBox info = new VBox(3);
        Label nameLabel = new Label(img.getFileName());
        nameLabel.setStyle("-fx-font-weight: bold;");

        Label pathLabel = new Label(img.getFilePath());
        pathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        pathLabel.setWrapText(true);

        String details = "";
        if (img.getFileSize() != null) {
            details += img.getFileSizeString();
        }
        if (img.getDateTaken() != null) {
            details += "  |  " + img.getDateTaken().toString();
        }
        if (img.getDimensionsString() != null && !img.getDimensionsString().isEmpty()) {
            details += "  |  " + img.getDimensionsString();
        }
        Label detailLabel = new Label(details);
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        info.getChildren().addAll(nameLabel, pathLabel, detailLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        row.getChildren().addAll(checkBox, imageView, info);
        return row;
    }

    private void deleteSelected(OpenSearchService.DuplicateGroup group, List<CheckBox> checkBoxes) {
        List<ImageMetadata> toDelete = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isSelected()) {
                toDelete.add(group.getImages().get(i));
            }
        }

        if (toDelete.isEmpty()) return;

        // Confirmation dialog
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Delete");
        confirm.setHeaderText("Delete " + toDelete.size() + " file(s)?");

        StringBuilder sb = new StringBuilder();
        for (ImageMetadata img : toDelete) {
            sb.append(img.getFilePath()).append("\n");
        }
        confirm.setContentText(sb.toString());

        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                int deleted = 0;
                int errors = 0;
                for (ImageMetadata img : toDelete) {
                    try {
                        Path path = Path.of(img.getFilePath());
                        Files.deleteIfExists(path);
                        openSearchService.deleteDocument(img.getFilePath());
                        deleted++;
                    } catch (Exception e) {
                        errors++;
                        System.err.println("Failed to delete " + img.getFilePath() + ": " + e.getMessage());
                    }
                }

                String msg = "Deleted " + deleted + " file(s)";
                if (errors > 0) msg += ", " + errors + " error(s)";
                summaryLabel.setText(msg + ". Click 'Scan for Duplicates' to refresh.");

                // Re-scan
                scanForDuplicates();
            }
        });
    }

    /**
     * Called when the tab is selected to refresh if needed.
     */
    public void refresh() {
        // No auto-refresh — user clicks scan button
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Custom cell renderer for duplicate groups in the list view.
     */
    private class DuplicateGroupCell extends ListCell<OpenSearchService.DuplicateGroup> {
        @Override
        protected void updateItem(OpenSearchService.DuplicateGroup group, boolean empty) {
            super.updateItem(group, empty);
            if (empty || group == null) {
                setText(null);
                setGraphic(null);
            } else {
                VBox box = new VBox(2);
                // Show first filename as representative
                String representative = group.getImages().isEmpty() ? "Unknown" :
                        group.getImages().get(0).getFileName();
                Label nameLabel = new Label(representative + " (" + group.getCount() + " copies)");
                nameLabel.setStyle("-fx-font-weight: bold;");

                Label sizeLabel = new Label(formatSize(group.getTotalSize()) +
                        " total, " + formatSize(group.getReclaimableSize()) + " reclaimable");
                sizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

                box.getChildren().addAll(nameLabel, sizeLabel);
                setGraphic(box);
            }
        }
    }
}
