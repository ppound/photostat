package com.photostat.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Dialog for browsing and selecting directories.
 */
public class DirectoryBrowserDialog extends Dialog<String> {

    private TreeView<FileTreeItem> directoryTree;
    private TextField pathField;
    private String selectedPath;

    public DirectoryBrowserDialog(String initialPath) {
        setTitle("Select Directory");
        setHeaderText("Choose a directory to index");
        setResizable(true);

        // Dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Create content
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(500);
        content.setPrefHeight(400);

        // Path field
        pathField = new TextField(initialPath);
        pathField.setPromptText("Enter path or select from tree below");
        pathField.setOnAction(e -> navigateToPath(pathField.getText()));

        Button goButton = new Button("Go");
        goButton.setOnAction(e -> navigateToPath(pathField.getText()));

        HBox pathBox = new HBox(10, pathField, goButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        // Directory tree
        directoryTree = new TreeView<>();
        directoryTree.setShowRoot(true);
        VBox.setVgrow(directoryTree, Priority.ALWAYS);

        // Set up root
        setupTreeRoot(initialPath);

        // Selection listener
        directoryTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedPath = newVal.getValue().getPath();
                pathField.setText(selectedPath);
            }
        });

        // Double-click to expand/select
        directoryTree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<FileTreeItem> selected = directoryTree.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    selected.setExpanded(!selected.isExpanded());
                }
            }
        });

        content.getChildren().addAll(new Label("Path:"), pathBox, directoryTree);

        getDialogPane().setContent(content);

        // Convert result
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return pathField.getText();
            }
            return null;
        });

        // Validate OK button
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);

        pathField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean validPath = newVal != null && !newVal.trim().isEmpty() &&
                    Files.isDirectory(Path.of(newVal.trim()));
            okButton.setDisable(!validPath);
        });

        // Trigger initial validation
        pathField.setText(initialPath);
    }

    private void setupTreeRoot(String initialPath) {
        // Determine root based on OS
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows - show drives
            TreeItem<FileTreeItem> root = new TreeItem<>(new FileTreeItem("Computer", ""));
            root.setExpanded(true);

            File[] roots = File.listRoots();
            for (File drive : roots) {
                TreeItem<FileTreeItem> driveItem = createTreeItem(drive);
                root.getChildren().add(driveItem);
            }

            directoryTree.setRoot(root);

        } else {
            // Unix-like - start from root
            File rootFile = new File("/");
            TreeItem<FileTreeItem> root = createTreeItem(rootFile);
            root.setExpanded(true);
            directoryTree.setRoot(root);
        }

        // Navigate to initial path
        if (initialPath != null && !initialPath.isEmpty()) {
            navigateToPath(initialPath);
        }
    }

    private TreeItem<FileTreeItem> createTreeItem(File file) {
        FileTreeItem item = new FileTreeItem(file.getName().isEmpty() ?
                file.getPath() : file.getName(), file.getAbsolutePath());

        TreeItem<FileTreeItem> treeItem = new TreeItem<>(item);

        // Add dummy child if directory has subdirectories
        if (file.isDirectory()) {
            treeItem.getChildren().add(new TreeItem<>(new FileTreeItem("Loading...", "")));

            // Lazy loading of children
            treeItem.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (isExpanded && !treeItem.getChildren().isEmpty() &&
                        treeItem.getChildren().get(0).getValue().getName().equals("Loading...")) {
                    loadChildren(treeItem);
                }
            });
        }

        return treeItem;
    }

    private void loadChildren(TreeItem<FileTreeItem> parent) {
        parent.getChildren().clear();

        String path = parent.getValue().getPath();
        File dir = new File(path);
        File[] files = dir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.isHidden()) {
                    try {
                        // Check if we can access this directory
                        if (Files.isReadable(file.toPath())) {
                            parent.getChildren().add(createTreeItem(file));
                        }
                    } catch (Exception ignored) {
                        // Skip inaccessible directories
                    }
                }
            }

            // Sort alphabetically
            parent.getChildren().sort((a, b) ->
                    a.getValue().getName().compareToIgnoreCase(b.getValue().getName()));
        }

        if (parent.getChildren().isEmpty()) {
            parent.getChildren().add(new TreeItem<>(new FileTreeItem("(empty)", "")));
        }
    }

    private void navigateToPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }

        Path targetPath = Path.of(path.trim());
        if (!Files.isDirectory(targetPath)) {
            return;
        }

        // Expand tree to show the path
        TreeItem<FileTreeItem> current = directoryTree.getRoot();
        current.setExpanded(true);

        String[] parts = path.split(File.separator.equals("\\") ? "\\\\" : File.separator);

        for (String part : parts) {
            if (part.isEmpty()) continue;

            // Handle Windows drive letters
            if (part.endsWith(":")) {
                part = part + File.separator;
            }

            final String searchPart = part;
            Optional<TreeItem<FileTreeItem>> found = current.getChildren().stream()
                    .filter(item -> {
                        String itemPath = item.getValue().getPath();
                        String itemName = item.getValue().getName();
                        return itemName.equalsIgnoreCase(searchPart) ||
                                itemPath.equalsIgnoreCase(searchPart) ||
                                itemPath.endsWith(File.separator + searchPart);
                    })
                    .findFirst();

            if (found.isPresent()) {
                current = found.get();
                current.setExpanded(true);

                // Force load children
                if (current.getChildren().size() == 1 &&
                        current.getChildren().get(0).getValue().getName().equals("Loading...")) {
                    loadChildren(current);
                }
            }
        }

        // Select the final item
        directoryTree.getSelectionModel().select(current);
        directoryTree.scrollTo(directoryTree.getRow(current));
        selectedPath = path;
    }

    /**
     * Simple class to hold file tree item data.
     */
    private static class FileTreeItem {
        private final String name;
        private final String path;

        public FileTreeItem(String name, String path) {
            this.name = name;
            this.path = path;
        }

        public String getName() {
            return name;
        }

        public String getPath() {
            return path;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
