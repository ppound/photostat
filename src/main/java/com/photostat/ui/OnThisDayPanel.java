package com.photostat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tab showing photos taken on a specific calendar day across all years.
 * Reuses ResultsPanel for the table and DetailPanel for selection display.
 */
public class OnThisDayPanel extends BorderPane {

    private static final DateTimeFormatter HEADER_DATE_FMT = DateTimeFormatter.ofPattern("MMMM d");

    private DatePicker datePicker;
    private Spinner<Integer> windowSpinner;
    private Label headerLabel;
    private ResultsPanel resultsPanel;
    private DetailPanel detailPanel;

    public OnThisDayPanel() {
        initializeUI();
    }

    private void initializeUI() {
        datePicker = new DatePicker(LocalDate.now());
        datePicker.setPrefWidth(150);
        datePicker.setTooltip(new Tooltip("Year is ignored — photos taken on this month/day in any year are shown."));

        windowSpinner = new Spinner<>(0, 30, 0, 1);
        windowSpinner.setEditable(true);
        windowSpinner.setPrefWidth(80);
        windowSpinner.setTooltip(new Tooltip("Also include photos within ± this many calendar days."));

        Button todayButton = new Button("Today");
        todayButton.setOnAction(e -> {
            datePicker.setValue(LocalDate.now());
            runSearch();
        });

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> runSearch());

        datePicker.valueProperty().addListener((obs, oldV, newV) -> runSearch());
        windowSpinner.valueProperty().addListener((obs, oldV, newV) -> runSearch());

        HBox controls = new HBox(10,
                new Label("Date:"), datePicker,
                new Label("± days:"), windowSpinner,
                todayButton, refreshButton);
        controls.setPadding(new Insets(10, 10, 5, 10));
        controls.setAlignment(Pos.CENTER_LEFT);

        headerLabel = new Label("");
        headerLabel.setStyle("-fx-font-weight: bold;");
        headerLabel.setWrapText(true);
        HBox headerBox = new HBox(headerLabel);
        headerBox.setPadding(new Insets(0, 10, 10, 10));

        VBox top = new VBox(controls, headerBox);
        setTop(top);

        resultsPanel = new ResultsPanel();
        detailPanel = new DetailPanel();

        resultsPanel.setSelectionCallback(metadata -> {
            if (metadata != null) {
                detailPanel.showMetadata(metadata);
            }
        });
        resultsPanel.setAggregationsCallback(this::updateHeader);
        resultsPanel.setRatingChangedCallback(detailPanel::updateRatingDisplay);
        detailPanel.setMetadataSavedCallback(() -> resultsPanel.refreshTableDisplay());

        detailPanel.setMinWidth(300);
        detailPanel.setPrefWidth(350);
        detailPanel.setMaxWidth(500);

        VBox resultsBox = new VBox(resultsPanel);
        resultsBox.setPadding(new Insets(0, 10, 10, 10));
        VBox.setVgrow(resultsPanel, Priority.ALWAYS);

        SplitPane split = new SplitPane();
        split.getItems().addAll(resultsBox, detailPanel);
        split.setDividerPositions(0.75);
        SplitPane.setResizableWithParent(detailPanel, false);

        setCenter(split);
    }

    private void runSearch() {
        LocalDate d = datePicker.getValue();
        if (d == null) {
            return;
        }
        int window = windowSpinner.getValue() != null ? windowSpinner.getValue() : 0;
        Map<String, Object> filters = new HashMap<>();
        filters.put("on_this_day",
                String.format("%02d-%02d:%d", d.getMonthValue(), d.getDayOfMonth(), window));
        resultsPanel.search(null, filters);
    }

    private void updateHeader(Map<String, Map<String, Long>> aggregations) {
        LocalDate d = datePicker.getValue();
        String dateLabel = d != null ? d.format(HEADER_DATE_FMT) : "";

        if (aggregations == null) {
            headerLabel.setText("");
            return;
        }
        Map<String, Long> yearBuckets = aggregations.get("year");
        if (yearBuckets == null || yearBuckets.isEmpty()) {
            headerLabel.setText("No photos found for " + dateLabel + ".");
            return;
        }

        // Date histogram keys look like "2024-01-01T00:00:00.000Z" — extract year.
        Map<Integer, Long> byYear = new TreeMap<>(Collections.reverseOrder());
        long total = 0;
        for (Map.Entry<String, Long> entry : yearBuckets.entrySet()) {
            try {
                int year = Integer.parseInt(entry.getKey().substring(0, 4));
                byYear.merge(year, entry.getValue(), Long::sum);
                total += entry.getValue();
            } catch (Exception ignored) {
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d photo%s across %d year%s on %s — ",
                total, total == 1 ? "" : "s",
                byYear.size(), byYear.size() == 1 ? "" : "s",
                dateLabel));

        int shown = 0;
        for (Map.Entry<Integer, Long> e : byYear.entrySet()) {
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
            shown++;
            if (shown >= 8 && byYear.size() > 8) {
                sb.append("…");
                break;
            }
        }
        headerLabel.setText(sb.toString());
    }

    /**
     * Refresh the panel — called when the tab becomes active.
     */
    public void refresh() {
        runSearch();
    }
}
