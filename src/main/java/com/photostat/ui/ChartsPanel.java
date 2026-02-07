package com.photostat.ui;

import com.photostat.services.OpenSearchService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Panel containing charts and visualizations of the indexed data.
 */
public class ChartsPanel extends BorderPane {

    private final OpenSearchService openSearchService;

    private TabPane chartTabs;
    private BarChart<String, Number> cameraMakeChart;
    private BarChart<String, Number> cameraModelChart;
    private BarChart<String, Number> lensChart;
    private BarChart<String, Number> softwareChart;
    private PieChart fileTypeChart;
    private LineChart<String, Number> timelineChart;
    private BarChart<String, Number> isoChart;
    private BarChart<String, Number> apertureChart;
    private BarChart<String, Number> focalLengthChart;

    public ChartsPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setPadding(new Insets(10));

        chartTabs = new TabPane();
        chartTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Overview tab
        Tab overviewTab = new Tab("Overview");
        overviewTab.setContent(createOverviewTab());

        // Equipment tab
        Tab equipmentTab = new Tab("Equipment");
        equipmentTab.setContent(createEquipmentTab());

        // Timeline tab
        Tab timelineTab = new Tab("Timeline");
        timelineTab.setContent(createTimelineTab());

        // Exposure tab
        Tab exposureTab = new Tab("Exposure");
        exposureTab.setContent(createExposureTab());

        chartTabs.getTabs().addAll(overviewTab, equipmentTab, timelineTab, exposureTab);

        setCenter(chartTabs);
    }

    private VBox createOverviewTab() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(10));

        // Camera makes bar chart
        CategoryAxis cameraMakeXAxis = new CategoryAxis();
        cameraMakeXAxis.setLabel("Camera Make");
        NumberAxis cameraMakeYAxis = new NumberAxis();
        cameraMakeYAxis.setLabel("Photo Count");

        cameraMakeChart = new BarChart<>(cameraMakeXAxis, cameraMakeYAxis);
        cameraMakeChart.setTitle("Photos by Camera Make");
        cameraMakeChart.setLegendVisible(false);
        cameraMakeChart.setPrefHeight(300);

        // File type pie chart
        fileTypeChart = new PieChart();
        fileTypeChart.setTitle("Photos by File Type");
        fileTypeChart.setLegendSide(Side.RIGHT);
        fileTypeChart.setPrefHeight(300);

        // Software bar chart
        CategoryAxis softwareXAxis = new CategoryAxis();
        softwareXAxis.setLabel("Software");
        NumberAxis softwareYAxis = new NumberAxis();
        softwareYAxis.setLabel("Photo Count");

        softwareChart = new BarChart<>(softwareXAxis, softwareYAxis);
        softwareChart.setTitle("Photos by Editing Software");
        softwareChart.setLegendVisible(false);
        softwareChart.setPrefHeight(300);

        content.getChildren().addAll(cameraMakeChart, fileTypeChart, softwareChart);
        VBox.setVgrow(cameraMakeChart, Priority.ALWAYS);
        VBox.setVgrow(fileTypeChart, Priority.ALWAYS);
        VBox.setVgrow(softwareChart, Priority.ALWAYS);

        return content;
    }

    private VBox createEquipmentTab() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(10));

        // Camera model bar chart
        CategoryAxis cameraModelXAxis = new CategoryAxis();
        cameraModelXAxis.setLabel("Camera Model");
        NumberAxis cameraModelYAxis = new NumberAxis();
        cameraModelYAxis.setLabel("Photo Count");

        cameraModelChart = new BarChart<>(cameraModelXAxis, cameraModelYAxis);
        cameraModelChart.setTitle("Photos by Camera Model");
        cameraModelChart.setLegendVisible(false);
        cameraModelChart.setPrefHeight(300);

        // Lens bar chart
        CategoryAxis lensXAxis = new CategoryAxis();
        lensXAxis.setLabel("Lens");
        NumberAxis lensYAxis = new NumberAxis();
        lensYAxis.setLabel("Photo Count");

        lensChart = new BarChart<>(lensXAxis, lensYAxis);
        lensChart.setTitle("Photos by Lens");
        lensChart.setLegendVisible(false);
        lensChart.setPrefHeight(300);

        content.getChildren().addAll(cameraModelChart, lensChart);
        VBox.setVgrow(cameraModelChart, Priority.ALWAYS);
        VBox.setVgrow(lensChart, Priority.ALWAYS);

        return content;
    }

    private VBox createTimelineTab() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(10));

        // Timeline line chart
        CategoryAxis timeXAxis = new CategoryAxis();
        timeXAxis.setLabel("Month");
        NumberAxis timeYAxis = new NumberAxis();
        timeYAxis.setLabel("Photo Count");

        timelineChart = new LineChart<>(timeXAxis, timeYAxis);
        timelineChart.setTitle("Photos Over Time");
        timelineChart.setLegendVisible(false);
        timelineChart.setCreateSymbols(true);
        VBox.setVgrow(timelineChart, Priority.ALWAYS);

        content.getChildren().add(timelineChart);

        return content;
    }

    private VBox createExposureTab() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(10));

        // ISO distribution
        CategoryAxis isoXAxis = new CategoryAxis();
        isoXAxis.setLabel("ISO");
        NumberAxis isoYAxis = new NumberAxis();
        isoYAxis.setLabel("Photo Count");

        isoChart = new BarChart<>(isoXAxis, isoYAxis);
        isoChart.setTitle("ISO Distribution");
        isoChart.setLegendVisible(false);
        isoChart.setPrefHeight(250);

        // Aperture distribution
        CategoryAxis apertureXAxis = new CategoryAxis();
        apertureXAxis.setLabel("Aperture (f/)");
        NumberAxis apertureYAxis = new NumberAxis();
        apertureYAxis.setLabel("Photo Count");

        apertureChart = new BarChart<>(apertureXAxis, apertureYAxis);
        apertureChart.setTitle("Aperture Distribution");
        apertureChart.setLegendVisible(false);
        apertureChart.setPrefHeight(250);

        // Focal length distribution
        CategoryAxis focalXAxis = new CategoryAxis();
        focalXAxis.setLabel("Focal Length (mm)");
        NumberAxis focalYAxis = new NumberAxis();
        focalYAxis.setLabel("Photo Count");

        focalLengthChart = new BarChart<>(focalXAxis, focalYAxis);
        focalLengthChart.setTitle("Focal Length Distribution");
        focalLengthChart.setLegendVisible(false);
        focalLengthChart.setPrefHeight(250);

        content.getChildren().addAll(isoChart, apertureChart, focalLengthChart);

        return content;
    }

    /**
     * Refresh all charts with current data.
     */
    public void refresh() {
        Thread thread = new Thread(() -> {
            try {
                if (!openSearchService.isConnected()) {
                    openSearchService.connect();
                }

                Map<String, Map<String, Long>> chartData = openSearchService.getChartData();

                Platform.runLater(() -> {
                    updateCameraMakeChart(chartData.get("camera_make"));
                    updateCameraModelChart(chartData.get("camera_model"));
                    updateLensChart(chartData.get("lens_model"));
                    updateSoftwareChart(chartData.get("software"));
                    updateFileTypeChart(chartData.get("file_type"));
                    updateTimelineChart(chartData.get("monthly"));
                    updateIsoChart(chartData.get("iso"));
                    updateApertureChart(chartData.get("aperture"));
                    updateFocalLengthChart(chartData.get("focal_length"));
                });

            } catch (Exception e) {
                System.err.println("Error loading chart data: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void updateCameraMakeChart(Map<String, Long> data) {
        cameraMakeChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort by count and take top 10
        data.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> {
                    series.getData().add(new XYChart.Data<>(entry.getKey(), entry.getValue()));
                });

        cameraMakeChart.getData().add(series);
    }

    private void updateCameraModelChart(Map<String, Long> data) {
        cameraModelChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort by count and take top 10
        data.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> {
                    series.getData().add(new XYChart.Data<>(entry.getKey(), entry.getValue()));
                });

        cameraModelChart.getData().add(series);
    }

    private void updateLensChart(Map<String, Long> data) {
        lensChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort by count and take top 10
        data.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> {
                    // Truncate long lens names for display
                    String label = entry.getKey();
                    if (label.length() > 25) {
                        label = label.substring(0, 22) + "...";
                    }
                    series.getData().add(new XYChart.Data<>(label, entry.getValue()));
                });

        lensChart.getData().add(series);
    }

    private void updateSoftwareChart(Map<String, Long> data) {
        softwareChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort by count and take top 10
        data.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> {
                    // Truncate long software names for display
                    String label = entry.getKey();
                    if (label.length() > 25) {
                        label = label.substring(0, 22) + "...";
                    }
                    series.getData().add(new XYChart.Data<>(label, entry.getValue()));
                });

        softwareChart.getData().add(series);
    }

    private void updateFileTypeChart(Map<String, Long> data) {
        fileTypeChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        ObservableList<PieChart.Data> pieData = FXCollections.observableArrayList();

        data.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> {
                    String label = entry.getKey().toUpperCase();
                    pieData.add(new PieChart.Data(label + " (" + entry.getValue() + ")", entry.getValue()));
                });

        fileTypeChart.setData(pieData);
    }

    private void updateTimelineChart(Map<String, Long> data) {
        timelineChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Photos");

        // Sort by date
        Map<String, Long> sortedData = new LinkedHashMap<>();
        data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sortedData.put(entry.getKey(), entry.getValue()));

        DateTimeFormatter inputFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        DateTimeFormatter outputFormat = DateTimeFormatter.ofPattern("MMM yyyy");

        sortedData.forEach((dateStr, count) -> {
            try {
                // Parse the date string and format for display
                String displayDate;
                if (dateStr.contains("T")) {
                    // Full ISO date
                    LocalDate date = LocalDate.parse(dateStr.substring(0, 10));
                    displayDate = date.format(outputFormat);
                } else {
                    displayDate = dateStr;
                }
                series.getData().add(new XYChart.Data<>(displayDate, count));
            } catch (Exception e) {
                series.getData().add(new XYChart.Data<>(dateStr, count));
            }
        });

        timelineChart.getData().add(series);
    }

    private void updateIsoChart(Map<String, Long> data) {
        isoChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort ISO values numerically and group into ranges
        Map<String, Long> isoRanges = new LinkedHashMap<>();
        isoRanges.put("100-199", 0L);
        isoRanges.put("200-399", 0L);
        isoRanges.put("400-799", 0L);
        isoRanges.put("800-1599", 0L);
        isoRanges.put("1600-3199", 0L);
        isoRanges.put("3200-6399", 0L);
        isoRanges.put("6400+", 0L);

        data.forEach((isoStr, count) -> {
            try {
                int iso = Integer.parseInt(isoStr.replaceAll("[^0-9]", ""));
                String range;
                if (iso < 200) range = "100-199";
                else if (iso < 400) range = "200-399";
                else if (iso < 800) range = "400-799";
                else if (iso < 1600) range = "800-1599";
                else if (iso < 3200) range = "1600-3199";
                else if (iso < 6400) range = "3200-6399";
                else range = "6400+";

                isoRanges.merge(range, count, Long::sum);
            } catch (NumberFormatException ignored) {}
        });

        isoRanges.forEach((range, count) -> {
            if (count > 0) {
                series.getData().add(new XYChart.Data<>(range, count));
            }
        });

        isoChart.getData().add(series);
    }

    private void updateApertureChart(Map<String, Long> data) {
        apertureChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort and display aperture values
        data.entrySet().stream()
                .sorted((a, b) -> {
                    try {
                        double aVal = Double.parseDouble(a.getKey().replaceAll("[^0-9.]", ""));
                        double bVal = Double.parseDouble(b.getKey().replaceAll("[^0-9.]", ""));
                        return Double.compare(aVal, bVal);
                    } catch (Exception e) {
                        return a.getKey().compareTo(b.getKey());
                    }
                })
                .filter(entry -> entry.getValue() > 0)
                .limit(15)
                .forEach(entry -> {
                    String label;
                    try {
                        double aperture = Double.parseDouble(entry.getKey().replaceAll("[^0-9.]", ""));
                        label = String.format("f/%.1f", aperture);
                    } catch (Exception e) {
                        label = entry.getKey();
                    }
                    series.getData().add(new XYChart.Data<>(label, entry.getValue()));
                });

        apertureChart.getData().add(series);
    }

    private void updateFocalLengthChart(Map<String, Long> data) {
        focalLengthChart.getData().clear();

        if (data == null || data.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // Sort and display focal length values
        data.entrySet().stream()
                .sorted((a, b) -> {
                    try {
                        double aVal = Double.parseDouble(a.getKey().replaceAll("[^0-9.]", ""));
                        double bVal = Double.parseDouble(b.getKey().replaceAll("[^0-9.]", ""));
                        return Double.compare(aVal, bVal);
                    } catch (Exception e) {
                        return a.getKey().compareTo(b.getKey());
                    }
                })
                .filter(entry -> entry.getValue() > 0)
                .limit(15)
                .forEach(entry -> {
                    String label;
                    try {
                        double focal = Double.parseDouble(entry.getKey().replaceAll("[^0-9.]", ""));
                        label = String.format("%.0fmm", focal);
                    } catch (Exception e) {
                        label = entry.getKey();
                    }
                    series.getData().add(new XYChart.Data<>(label, entry.getValue()));
                });

        focalLengthChart.getData().add(series);
    }
}
