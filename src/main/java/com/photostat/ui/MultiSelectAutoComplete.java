package com.photostat.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A multi-select autocomplete component that displays selected values as removable chips
 * and provides autocomplete suggestions in a dropdown.
 */
public class MultiSelectAutoComplete extends VBox {

    private final FlowPane chipsPane;
    private final TextField inputField;
    private final ContextMenu suggestionsPopup;
    private final ObservableList<String> allItems;
    private final ObservableList<String> selectedItems;
    private final int maxSuggestions = 10;

    public MultiSelectAutoComplete() {
        this.allItems = FXCollections.observableArrayList();
        this.selectedItems = FXCollections.observableArrayList();
        this.suggestionsPopup = new ContextMenu();

        // Create chips container and input field in a flow layout
        chipsPane = new FlowPane();
        chipsPane.setHgap(4);
        chipsPane.setVgap(4);
        chipsPane.setPadding(new Insets(4));
        chipsPane.getStyleClass().add("chips-container");

        // Input field for typing
        inputField = new TextField();
        inputField.setPromptText("Type to search...");
        inputField.getStyleClass().add("chip-input");
        inputField.setMinWidth(80);
        inputField.setPrefWidth(100);

        // Add input field to chips pane
        chipsPane.getChildren().add(inputField);

        // Setup input field behavior
        setupInputField();

        // Add to this VBox
        getChildren().add(chipsPane);
        setMaxHeight(USE_PREF_SIZE);
    }

    private void setupInputField() {
        // Filter suggestions as user types
        inputField.textProperty().addListener((obs, oldText, newText) -> {
            if (newText == null || newText.isEmpty()) {
                suggestionsPopup.hide();
            } else {
                showSuggestions(newText);
            }
        });

        // Handle key events
        inputField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.TAB) {
                // Add the first suggestion or the typed text
                String text = inputField.getText().trim();
                if (!text.isEmpty()) {
                    if (!suggestionsPopup.getItems().isEmpty()) {
                        // Add first suggestion
                        MenuItem firstItem = suggestionsPopup.getItems().get(0);
                        addItem(firstItem.getText());
                    } else {
                        // Add typed text as new item
                        addItem(text);
                    }
                }
                event.consume();
            } else if (event.getCode() == KeyCode.BACK_SPACE && inputField.getText().isEmpty()) {
                // Remove last chip when backspace on empty field
                if (!selectedItems.isEmpty()) {
                    removeItem(selectedItems.get(selectedItems.size() - 1));
                }
            } else if (event.getCode() == KeyCode.ESCAPE) {
                suggestionsPopup.hide();
            } else if (event.getCode() == KeyCode.DOWN) {
                // Focus on suggestions popup
                if (suggestionsPopup.isShowing() && !suggestionsPopup.getItems().isEmpty()) {
                    suggestionsPopup.getItems().get(0).getStyleableNode().requestFocus();
                }
            }
        });

        // Show suggestions when field is focused and has text
        inputField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused && !inputField.getText().isEmpty()) {
                showSuggestions(inputField.getText());
            } else if (!isFocused) {
                // Delay hiding to allow click on suggestion
                javafx.application.Platform.runLater(() -> {
                    if (!suggestionsPopup.isShowing()) return;
                    suggestionsPopup.hide();
                });
            }
        });

        // Show all options when clicking on empty field
        inputField.setOnMouseClicked(event -> {
            if (inputField.getText().isEmpty()) {
                showSuggestions("");
            }
        });
    }

    private void showSuggestions(String filter) {
        suggestionsPopup.getItems().clear();

        String lowerFilter = filter.toLowerCase();
        List<String> matches = allItems.stream()
                .filter(item -> !selectedItems.contains(item)) // Exclude already selected
                .filter(item -> filter.isEmpty() || item.toLowerCase().contains(lowerFilter))
                .limit(maxSuggestions)
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            suggestionsPopup.hide();
            return;
        }

        for (String match : matches) {
            MenuItem item = new MenuItem(match);
            item.setOnAction(e -> addItem(match));
            suggestionsPopup.getItems().add(item);
        }

        if (!suggestionsPopup.isShowing()) {
            suggestionsPopup.show(inputField, Side.BOTTOM, 0, 0);
        }
    }

    private void addItem(String item) {
        if (item == null || item.trim().isEmpty()) return;
        item = item.trim();

        if (selectedItems.contains(item)) {
            // Already selected
            inputField.clear();
            suggestionsPopup.hide();
            return;
        }

        selectedItems.add(item);

        // Create chip
        HBox chip = createChip(item);

        // Insert chip before the input field
        int inputIndex = chipsPane.getChildren().indexOf(inputField);
        chipsPane.getChildren().add(inputIndex, chip);

        // Clear input and hide suggestions
        inputField.clear();
        suggestionsPopup.hide();
        inputField.requestFocus();
    }

    private void removeItem(String item) {
        selectedItems.remove(item);

        // Find and remove the chip
        chipsPane.getChildren().removeIf(node -> {
            if (node instanceof HBox) {
                HBox chip = (HBox) node;
                if (chip.getChildren().size() > 0 && chip.getChildren().get(0) instanceof Label) {
                    Label label = (Label) chip.getChildren().get(0);
                    return label.getText().equals(item);
                }
            }
            return false;
        });

        inputField.requestFocus();
    }

    private HBox createChip(String text) {
        HBox chip = new HBox(4);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(2, 6, 2, 8));
        chip.getStyleClass().add("chip");

        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px;");

        Button removeBtn = new Button("\u00D7"); // × character
        removeBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 0 4; -fx-font-size: 12px; -fx-cursor: hand;");
        removeBtn.setOnAction(e -> removeItem(text));

        chip.getChildren().addAll(label, removeBtn);
        return chip;
    }

    /**
     * Set the available items for autocomplete suggestions.
     */
    public void setItems(List<String> items) {
        allItems.clear();
        if (items != null) {
            allItems.addAll(items);
        }
    }

    /**
     * Get the list of selected items.
     */
    public List<String> getSelectedItems() {
        return new ArrayList<>(selectedItems);
    }

    /**
     * Set the selected items (will create chips for each).
     */
    public void setSelectedItems(List<String> items) {
        // Clear existing chips (except input field)
        chipsPane.getChildren().removeIf(node -> node instanceof HBox);
        selectedItems.clear();

        if (items != null) {
            for (String item : items) {
                addItem(item);
            }
        }
    }

    /**
     * Clear all selected items.
     */
    public void clear() {
        chipsPane.getChildren().removeIf(node -> node instanceof HBox);
        selectedItems.clear();
        inputField.clear();
        suggestionsPopup.hide();
    }

    /**
     * Get the selected items as a single comma-separated string.
     */
    public String getSelectedAsString() {
        if (selectedItems.isEmpty()) {
            return "";
        }
        return String.join(", ", selectedItems);
    }

    /**
     * Check if any items are selected.
     */
    public boolean hasSelection() {
        return !selectedItems.isEmpty();
    }

    /**
     * Set the prompt text shown when no items are selected.
     */
    public void setPromptText(String text) {
        inputField.setPromptText(text);
    }

    /**
     * Request focus on the input field.
     */
    @Override
    public void requestFocus() {
        inputField.requestFocus();
    }
}
