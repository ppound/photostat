package com.photostat.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Attaches autocomplete dropdown suggestions to a TextField.
 * Supports comma-separated mode (persons, tags) and single-value mode (place).
 */
public class AutoCompleteHelper {

    private static final int MAX_SUGGESTIONS = 10;

    /**
     * Attach autocomplete behavior to a TextField.
     *
     * @param field          the text field to enhance
     * @param suggestions    supplier of suggestion values (evaluated each time)
     * @param commaSeparated true for comma-separated fields (persons, tags); false for single-value (place)
     */
    public static void attach(TextField field, java.util.function.Supplier<List<String>> suggestions, boolean commaSeparated) {
        ContextMenu popup = new ContextMenu();
        popup.setAutoHide(true);

        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isEmpty()) {
                popup.hide();
                return;
            }

            // Don't show popup if field doesn't have focus (e.g., programmatic setText)
            if (!field.isFocused()) {
                popup.hide();
                return;
            }

            String filterText;
            Set<String> alreadyPresent;

            if (commaSeparated) {
                // Extract the token after the last comma
                int lastComma = newVal.lastIndexOf(',');
                filterText = (lastComma >= 0 ? newVal.substring(lastComma + 1) : newVal).trim();
                // Collect already-entered values (before the current token)
                alreadyPresent = Arrays.stream(newVal.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
                // Remove the current token from "already present" so it can still match
                alreadyPresent.remove(filterText);
            } else {
                filterText = newVal.trim();
                alreadyPresent = Set.of();
            }

            if (filterText.isEmpty()) {
                popup.hide();
                return;
            }

            String lowerFilter = filterText.toLowerCase();
            List<String> currentSuggestions = suggestions.get();
            if (currentSuggestions == null) {
                popup.hide();
                return;
            }

            List<String> matches = currentSuggestions.stream()
                    .filter(s -> s.toLowerCase().contains(lowerFilter))
                    .filter(s -> !alreadyPresent.contains(s))
                    .limit(MAX_SUGGESTIONS)
                    .collect(Collectors.toList());

            if (matches.isEmpty()) {
                popup.hide();
                return;
            }

            popup.getItems().clear();
            for (String match : matches) {
                MenuItem item = new MenuItem(match);
                item.setOnAction(e -> {
                    if (commaSeparated) {
                        // Replace the current token with the selected value
                        int lastComma = field.getText().lastIndexOf(',');
                        String prefix = lastComma >= 0 ? field.getText().substring(0, lastComma + 1) + " " : "";
                        field.setText(prefix + match + ", ");
                        field.positionCaret(field.getText().length());
                    } else {
                        field.setText(match);
                        field.positionCaret(match.length());
                    }
                    popup.hide();
                });
                popup.getItems().add(item);
            }

            if (!popup.isShowing()) {
                popup.show(field, javafx.geometry.Side.BOTTOM, 0, 0);
            }
        });

        // Hide on escape
        field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && popup.isShowing()) {
                popup.hide();
                e.consume();
            }
        });

        // Hide when field loses focus
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                popup.hide();
            }
        });
    }
}
