package com.photostat.ui;

import com.photostat.models.ImageMetadata;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dialog for batch-renaming images via substring or regex find/replace.
 * Operates on either the user's current selection or the full current result
 * set; shows a live preview with conflict detection before the user applies.
 */
public class BatchRenameDialog extends Dialog<BatchRenameDialog.Result> {

    /**
     * What the user chose to apply: a map of full image path to new basename.
     * Only entries whose name actually changes (and have no conflict) are
     * included.
     */
    public static class Result {
        private final Map<String, String> renames;

        public Result(Map<String, String> renames) {
            this.renames = renames;
        }

        public Map<String, String> getRenames() {
            return renames;
        }
    }

    private enum Status {
        RENAME, UNCHANGED, CONFLICT_EXISTS, CONFLICT_DUPLICATE
    }

    /** Row model for the preview table. Backed by a full path so we can build the result map. */
    public static class RenamePreview {
        private final String fullPath;
        private final SimpleStringProperty currentName;
        private final SimpleStringProperty newName;
        private final SimpleStringProperty status;
        private Status statusCode;

        public RenamePreview(String fullPath, String currentName, String newName, Status status) {
            this.fullPath = fullPath;
            this.currentName = new SimpleStringProperty(currentName);
            this.newName = new SimpleStringProperty(newName);
            this.statusCode = status;
            this.status = new SimpleStringProperty(statusLabel(status, newName));
        }

        public String getFullPath() { return fullPath; }
        public String getCurrentName() { return currentName.get(); }
        public String getNewName() { return newName.get(); }
        public String getStatus() { return status.get(); }
        public Status getStatusCode() { return statusCode; }

        private static String statusLabel(Status s, String newName) {
            switch (s) {
                case RENAME: return "Will rename to " + newName;
                case UNCHANGED: return "No match";
                case CONFLICT_EXISTS: return "Conflict: target exists";
                case CONFLICT_DUPLICATE: return "Conflict: duplicate target";
                default: return "";
            }
        }
    }

    private final List<ImageMetadata> selected;
    private final List<ImageMetadata> currentResults;
    private final RadioButton sourceSelectedRadio;
    private final RadioButton sourceResultsRadio;
    private final TextField findField;
    private final TextField replaceField;
    private final CheckBox regexCheckbox;
    private final Label statusLine;
    private final TableView<RenamePreview> previewTable;
    private final ObservableList<RenamePreview> previewRows = FXCollections.observableArrayList();
    private final Button applyButton;

    public BatchRenameDialog(List<ImageMetadata> selected, List<ImageMetadata> currentResults) {
        this.selected = selected != null ? selected : new ArrayList<>();
        this.currentResults = currentResults != null ? currentResults : new ArrayList<>();

        setTitle("Batch Rename");
        setHeaderText("Find and replace in filenames");
        setResizable(true);

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) getDialogPane().lookupButton(ButtonType.OK)).setText("Apply");
        applyButton = (Button) getDialogPane().lookupButton(ButtonType.OK);

        // --- Source selector ---
        ToggleGroup sourceGroup = new ToggleGroup();
        sourceSelectedRadio = new RadioButton("Selected (" + this.selected.size() + ")");
        sourceSelectedRadio.setToggleGroup(sourceGroup);
        sourceResultsRadio = new RadioButton("Current results (" + this.currentResults.size() + ")");
        sourceResultsRadio.setToggleGroup(sourceGroup);

        // Default: selected if non-empty, else current results.
        if (!this.selected.isEmpty()) {
            sourceSelectedRadio.setSelected(true);
        } else {
            sourceResultsRadio.setSelected(true);
        }
        sourceSelectedRadio.setDisable(this.selected.isEmpty());
        sourceResultsRadio.setDisable(this.currentResults.isEmpty());

        HBox sourceBox = new HBox(15, new Label("Source:"), sourceSelectedRadio, sourceResultsRadio);

        // --- Find/Replace ---
        findField = new TextField();
        findField.setPromptText("e.g. DSC-");
        replaceField = new TextField();
        replaceField.setPromptText("e.g. Scotland-");
        regexCheckbox = new CheckBox("Regex");

        GridPane findGrid = new GridPane();
        findGrid.setHgap(8);
        findGrid.setVgap(6);
        findGrid.add(new Label("Find:"), 0, 0);
        findGrid.add(findField, 1, 0);
        findGrid.add(regexCheckbox, 2, 0);
        findGrid.add(new Label("Replace:"), 0, 1);
        findGrid.add(replaceField, 1, 1);
        GridPane.setHgrow(findField, Priority.ALWAYS);
        GridPane.setHgrow(replaceField, Priority.ALWAYS);

        // --- Preview table ---
        previewTable = new TableView<>(previewRows);
        previewTable.setPlaceholder(new Label("Enter a find string to preview renames."));

        TableColumn<RenamePreview, String> currentCol = new TableColumn<>("Current name");
        currentCol.setCellValueFactory(new PropertyValueFactory<>("currentName"));
        currentCol.setCellFactory(c -> statusStyledCell());
        currentCol.setPrefWidth(260);

        TableColumn<RenamePreview, String> newCol = new TableColumn<>("New name");
        newCol.setCellValueFactory(new PropertyValueFactory<>("newName"));
        newCol.setCellFactory(c -> statusStyledCell());
        newCol.setPrefWidth(260);

        TableColumn<RenamePreview, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setCellFactory(c -> statusStyledCell());
        statusCol.setPrefWidth(260);

        previewTable.getColumns().addAll(currentCol, newCol, statusCol);

        VBox.setVgrow(previewTable, Priority.ALWAYS);

        statusLine = new Label("Enter a find string to begin.");

        // --- Assemble ---
        VBox content = new VBox(10, sourceBox, findGrid, previewTable, statusLine);
        content.setPadding(new Insets(10));
        content.setPrefWidth(800);
        content.setPrefHeight(500);

        getDialogPane().setContent(content);

        // --- Live preview refresh on any input change ---
        Runnable refresh = this::refreshPreview;
        sourceSelectedRadio.selectedProperty().addListener((o, a, b) -> refresh.run());
        sourceResultsRadio.selectedProperty().addListener((o, a, b) -> refresh.run());
        findField.textProperty().addListener((o, a, b) -> refresh.run());
        replaceField.textProperty().addListener((o, a, b) -> refresh.run());
        regexCheckbox.selectedProperty().addListener((o, a, b) -> refresh.run());

        refreshPreview();

        // --- Result conversion ---
        setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) return null;
            Map<String, String> renames = new LinkedHashMap<>();
            for (RenamePreview row : previewRows) {
                if (row.getStatusCode() == Status.RENAME) {
                    renames.put(row.getFullPath(), row.getNewName());
                }
            }
            return new Result(renames);
        });
    }

    private List<ImageMetadata> currentSource() {
        return sourceSelectedRadio.isSelected() ? selected : currentResults;
    }

    /**
     * Cell that applies per-row styling based on the row's status. Background
     * is set on the cell (so it tiles across the row) and text-fill is set on
     * the cell directly so it wins over theme defaults.
     */
    private static TableCell<RenamePreview, String> statusStyledCell() {
        return new TableCell<RenamePreview, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setStyle("");
                    return;
                }
                RenamePreview row = (RenamePreview) getTableRow().getItem();
                switch (row.getStatusCode()) {
                    case CONFLICT_EXISTS:
                    case CONFLICT_DUPLICATE:
                        setStyle("-fx-background-color: #ffd6d6; -fx-text-fill: #8b0000;");
                        break;
                    case UNCHANGED:
                        setStyle("-fx-text-fill: #888888;");
                        break;
                    default:
                        setStyle("");
                }
            }
        };
    }

    private void refreshPreview() {
        previewRows.clear();
        String find = findField.getText();
        String replace = replaceField.getText() == null ? "" : replaceField.getText();

        if (find == null || find.isEmpty()) {
            statusLine.setText("Enter a find string to begin.");
            applyButton.setDisable(true);
            return;
        }

        // Compile regex up-front so we surface a clean error to the user.
        Pattern regex = null;
        if (regexCheckbox.isSelected()) {
            try {
                regex = Pattern.compile(find);
            } catch (PatternSyntaxException e) {
                statusLine.setText("Invalid regex: " + e.getDescription());
                applyButton.setDisable(true);
                return;
            }
        }

        List<ImageMetadata> source = currentSource();

        // Build proposed names, then detect collisions.
        Map<String, String> proposed = new LinkedHashMap<>(); // fullPath -> newBasename
        Map<String, String> currentNames = new HashMap<>();  // fullPath -> currentBasename
        for (ImageMetadata m : source) {
            String path = m.getFilePath();
            if (path == null) continue;
            String currentBasename = Path.of(path).getFileName().toString();
            String newBasename;
            if (regex != null) {
                newBasename = regex.matcher(currentBasename).replaceAll(replace);
            } else {
                newBasename = currentBasename.replace(find, replace);
            }
            proposed.put(path, newBasename);
            currentNames.put(path, currentBasename);
        }

        // Detect duplicate targets within this batch (multiple sources -> same new name in same directory).
        Map<String, Integer> targetCounts = new HashMap<>();
        for (Map.Entry<String, String> e : proposed.entrySet()) {
            Path parent = Path.of(e.getKey()).getParent();
            String key = (parent == null ? "" : parent.toString()) + "/" + e.getValue();
            targetCounts.merge(key, 1, Integer::sum);
        }

        // Existence-conflict: target file already exists on disk and isn't part of this batch
        // (a file renaming to its own current basename in another row's slot etc.)
        Set<String> batchSources = new HashSet<>(proposed.keySet());

        int renameCount = 0, unchangedCount = 0, conflictCount = 0;
        for (Map.Entry<String, String> e : proposed.entrySet()) {
            String fullPath = e.getKey();
            String currentBasename = currentNames.get(fullPath);
            String newBasename = e.getValue();
            Path parent = Path.of(fullPath).getParent();
            String targetKey = (parent == null ? "" : parent.toString()) + "/" + newBasename;
            Path targetPath = parent == null ? Path.of(newBasename) : parent.resolve(newBasename);

            Status status;
            if (newBasename.equals(currentBasename)) {
                status = Status.UNCHANGED;
                unchangedCount++;
            } else if (targetCounts.getOrDefault(targetKey, 0) > 1) {
                status = Status.CONFLICT_DUPLICATE;
                conflictCount++;
            } else if (java.nio.file.Files.exists(targetPath) && !batchSources.contains(targetPath.toString())) {
                status = Status.CONFLICT_EXISTS;
                conflictCount++;
            } else {
                status = Status.RENAME;
                renameCount++;
            }
            previewRows.add(new RenamePreview(fullPath, currentBasename, newBasename, status));
        }

        StringBuilder summary = new StringBuilder();
        summary.append(renameCount).append(" will rename");
        summary.append(", ").append(unchangedCount).append(" unchanged");
        if (conflictCount > 0) {
            summary.append(", ").append(conflictCount).append(" conflicts");
        }
        statusLine.setText(summary.toString());

        applyButton.setDisable(renameCount == 0 || conflictCount > 0);
    }
}
