package com.photostat.ui;

import com.photostat.services.AestheticService;
import com.photostat.services.DockerService;
import com.photostat.services.FaceRecognitionService;
import com.photostat.services.ImageAnalysisService;
import com.photostat.services.OpenSearchService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab for starting and stopping the Dockerised backend services.
 *
 * <p>Wraps {@link DockerService} so users never have to type a compose command.
 * Every Docker call is blocking, so all of them run on a daemon thread and post
 * results back with {@link Platform#runLater}.
 */
public class ServicesPanel extends BorderPane {

    /** Ports each service publishes, for display only. */
    private static final Map<String, Integer> SERVICE_PORTS = Map.of(
            "opensearch", 9200,
            "faces", 8001,
            "analysis", 8002,
            "aesthetic", 8003);

    private static final Map<String, String> SERVICE_DESCRIPTIONS = Map.of(
            "opensearch", "Search index (required)",
            "faces", "Face detection and clustering",
            "analysis", "Local tagging and captioning",
            "aesthetic", "Image-quality scoring");

    private final DockerService dockerService;

    private Label engineStateLabel;
    private Button startEngineButton;
    private Button startAllButton;
    private Button stopAllButton;
    private Button pullButton;
    private Button refreshButton;
    private TextArea logArea;

    private final Map<String, ServiceRow> rows = new LinkedHashMap<>();

    /** True while a Docker operation is in flight; disables the controls. */
    private volatile boolean busy = false;

    public ServicesPanel() {
        this.dockerService = DockerService.getInstance();
        initializeUI();
        refresh();
    }

    /** One row of the service table. */
    private static class ServiceRow {
        final Label state = new Label("—");
        final Label health = new Label("—");
        final Button action = new Button("Start");
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void initializeUI() {
        setTop(buildHeader());
        setCenter(buildServiceTable());
        setBottom(buildLogPane());
    }

    private VBox buildHeader() {
        engineStateLabel = new Label("Checking Docker...");
        engineStateLabel.setStyle("-fx-font-weight: bold;");

        startEngineButton = new Button("Start Docker");
        startEngineButton.setOnAction(e -> startEngine());
        startEngineButton.setVisible(false);
        startEngineButton.setManaged(false);

        HBox engineBox = new HBox(10, new Label("Docker engine:"), engineStateLabel, startEngineButton);
        engineBox.setAlignment(Pos.CENTER_LEFT);

        startAllButton = new Button("Start All");
        startAllButton.setOnAction(e -> startAll());

        stopAllButton = new Button("Stop All");
        stopAllButton.setOnAction(e -> stopServices(null, "Stopping all services"));

        pullButton = new Button("Check for Image Updates");
        pullButton.setOnAction(e -> pullImages());

        refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refresh());

        HBox actions = new HBox(10, startAllButton, stopAllButton, pullButton, refreshButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(
                "Services run in Docker and are published on 127.0.0.1, so only this machine can reach them.");
        note.getStyleClass().add("info-label-small");
        note.setWrapText(true);

        VBox header = new VBox(8, engineBox, actions, note, new Separator());
        header.setPadding(new Insets(10));
        return header;
    }

    private ScrollPane buildServiceTable() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        String[] headers = {"Service", "Port", "Container", "Health", ""};
        for (int col = 0; col < headers.length; col++) {
            Label label = new Label(headers[col]);
            label.setStyle("-fx-font-weight: bold;");
            grid.add(label, col, 0);
        }

        int rowIndex = 1;
        for (String service : DockerService.ALL_SERVICES) {
            ServiceRow row = new ServiceRow();
            rows.put(service, row);

            Label name = new Label(service);
            name.setStyle("-fx-font-weight: bold;");
            Label description = new Label(SERVICE_DESCRIPTIONS.getOrDefault(service, ""));
            description.getStyleClass().add("info-label-small");

            row.action.setPrefWidth(70);
            row.action.setOnAction(e -> toggleService(service));

            grid.add(new VBox(2, name, description), 0, rowIndex);
            grid.add(new Label(String.valueOf(SERVICE_PORTS.getOrDefault(service, 0))), 1, rowIndex);
            grid.add(row.state, 2, rowIndex);
            grid.add(row.health, 3, rowIndex);
            grid.add(row.action, 4, rowIndex);
            rowIndex++;
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private TitledPane buildLogPane() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(8);

        TitledPane pane = new TitledPane("Log", logArea);
        pane.setExpanded(false);
        return pane;
    }

    // ------------------------------------------------------------------
    // Status refresh
    // ------------------------------------------------------------------

    /** Re-read engine and container state. Called on tab activation. */
    public void refresh() {
        if (busy) {
            return;
        }
        setBusy(true);
        runInBackground("services-refresh", () -> {
            DockerService.EngineState engine = dockerService.getEngineState();
            Map<String, DockerService.ServiceStatus> statuses =
                    engine == DockerService.EngineState.RUNNING
                            ? dockerService.status()
                            : Map.of();

            // Only probe health for containers that are actually up.
            Map<String, Boolean> health = new LinkedHashMap<>();
            for (String service : DockerService.ALL_SERVICES) {
                DockerService.ServiceStatus status = statuses.get(service);
                health.put(service, status != null && status.isRunning() && checkHealth(service));
            }

            Platform.runLater(() -> {
                applyEngineState(engine);
                applyStatuses(statuses, health);
                setBusy(false);
            });
        });
    }

    /**
     * Ask the relevant service whether its container answers.
     *
     * <p>These reuse the existing /health probes rather than issuing new HTTP
     * calls, and deliberately ignore the configured backend mode so the panel
     * reports on the container even when the app is set to local Python.
     */
    private boolean checkHealth(String service) {
        try {
            switch (service) {
                case "opensearch":
                    return OpenSearchService.getInstance().testConnection();
                case "faces":
                    return FaceRecognitionService.getInstance().isDockerServiceHealthy();
                case "analysis":
                    return ImageAnalysisService.getInstance().isDockerServiceHealthy();
                case "aesthetic":
                    return AestheticService.getInstance().isAvailable();
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void applyEngineState(DockerService.EngineState engine) {
        boolean canOperate = engine == DockerService.EngineState.RUNNING;
        boolean showStartEngine = engine == DockerService.EngineState.STOPPED;

        switch (engine) {
            case RUNNING:
                engineStateLabel.setText("Running");
                engineStateLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: green;");
                break;
            case STOPPED:
                engineStateLabel.setText("Installed, but not running");
                engineStateLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: darkorange;");
                break;
            default:
                engineStateLabel.setText("Not installed");
                engineStateLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: red;");
                break;
        }

        startEngineButton.setVisible(showStartEngine);
        startEngineButton.setManaged(showStartEngine);

        if (!canOperate) {
            for (ServiceRow row : rows.values()) {
                row.state.setText("—");
                row.health.setText("—");
            }
        }

        startAllButton.setDisable(!canOperate);
        stopAllButton.setDisable(!canOperate);
        pullButton.setDisable(!canOperate);
        for (ServiceRow row : rows.values()) {
            row.action.setDisable(!canOperate);
        }

        if (engine == DockerService.EngineState.NOT_INSTALLED) {
            engineStateLabel.setTooltip(new Tooltip(
                    "Docker was not found on this machine. Install Docker Desktop to run the backend services."));
        }
    }

    private void applyStatuses(Map<String, DockerService.ServiceStatus> statuses,
                               Map<String, Boolean> health) {
        for (Map.Entry<String, ServiceRow> entry : rows.entrySet()) {
            String service = entry.getKey();
            ServiceRow row = entry.getValue();
            DockerService.ServiceStatus status = statuses.get(service);

            if (status == null || status.getState() == DockerService.ServiceState.ABSENT) {
                row.state.setText("not created");
                row.state.setStyle("-fx-text-fill: gray;");
                row.health.setText("—");
                row.health.setStyle("");
                row.action.setText("Start");
                continue;
            }

            boolean running = status.isRunning();
            row.state.setText(status.getStatusText().isEmpty()
                    ? status.getState().name().toLowerCase()
                    : status.getStatusText());
            row.state.setStyle(running ? "-fx-text-fill: green;" : "-fx-text-fill: gray;");
            row.action.setText(running ? "Stop" : "Start");

            if (!running) {
                row.health.setText("—");
                row.health.setStyle("");
            } else if (Boolean.TRUE.equals(health.get(service))) {
                row.health.setText("healthy");
                row.health.setStyle("-fx-text-fill: green;");
            } else {
                // Models download on first start, so this is normal for a while.
                row.health.setText("starting...");
                row.health.setStyle("-fx-text-fill: darkorange;");
            }
        }
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    private void startEngine() {
        setBusy(true);
        log("Starting the Docker engine. This can take up to a minute.");
        runInBackground("docker-engine-start", () -> {
            String error = dockerService.startEngine(this::log);
            Platform.runLater(() -> {
                setBusy(false);
                if (error != null) {
                    showError("Could not start Docker", error);
                }
                refresh();
            });
        });
    }

    private void startAll() {
        if (!dockerService.isComposeFileAvailable()) {
            showError("Compose file missing",
                    "Expected " + dockerService.getComposeFile()
                            + ".\nRestart PhotoStat to redeploy it.");
            return;
        }
        startServices(null, "Starting all services");
    }

    private void startServices(List<String> services, String message) {
        setBusy(true);
        log(message + "...");
        runInBackground("docker-up", () -> {
            // up -d also creates containers that do not exist yet, so it covers
            // both first run and a restart after Stop All.
            DockerService.CommandResult result = dockerService.composeUp(services, this::log);
            Platform.runLater(() -> {
                setBusy(false);
                if (!result.isSuccess()) {
                    showError("Could not start services", result.getError());
                }
                refresh();
            });
        });
    }

    private void stopServices(List<String> services, String message) {
        setBusy(true);
        log(message + "...");
        runInBackground("docker-stop", () -> {
            DockerService.CommandResult result = dockerService.composeStop(services, this::log);
            Platform.runLater(() -> {
                setBusy(false);
                if (!result.isSuccess()) {
                    showError("Could not stop services", result.getError());
                }
                refresh();
            });
        });
    }

    private void toggleService(String service) {
        ServiceRow row = rows.get(service);
        if (row == null) {
            return;
        }
        List<String> only = List.of(service);
        if ("Stop".equals(row.action.getText())) {
            stopServices(only, "Stopping " + service);
        } else {
            startServices(only, "Starting " + service);
        }
    }

    private void pullImages() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Check for Image Updates");
        confirm.setHeaderText("Download updated backend images?");
        confirm.getDialogPane().setMinWidth(480);
        confirm.setContentText(
                "This downloads the container images pinned to this PhotoStat version.\n\n"
                        + "The CPU images total several GB, and the GPU images are considerably "
                        + "larger. Images already downloaded are skipped, so this is usually much "
                        + "smaller after the first run.\n\n"
                        + "You can keep using PhotoStat while this runs.");
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        setBusy(true);
        log("Pulling images...");
        runInBackground("docker-pull", () -> {
            DockerService.CommandResult result = dockerService.composePull(null, this::log);
            Platform.runLater(() -> {
                setBusy(false);
                if (!result.isSuccess()) {
                    showError("Could not pull images", result.getError());
                }
                refresh();
            });
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void runInBackground(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Append a progress line to the log. Safe to call from any thread. */
    private void log(DockerService.DockerProgress progress) {
        log(progress.getStatusLine());
    }

    private void log(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            logArea.appendText(line + "\n");
            // Keep the log from growing without bound during a long pull.
            if (logArea.getLength() > 200_000) {
                logArea.deleteText(0, 100_000);
            }
        });
    }

    /**
     * Enable or disable the controls. Individual row buttons stay disabled when
     * the engine is unavailable, so re-enabling defers to the next refresh.
     */
    private void setBusy(boolean value) {
        busy = value;
        Runnable apply = () -> {
            startAllButton.setDisable(value);
            stopAllButton.setDisable(value);
            pullButton.setDisable(value);
            refreshButton.setDisable(value);
            startEngineButton.setDisable(value);
            for (ServiceRow row : rows.values()) {
                row.action.setDisable(value);
            }
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    private void showError(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Backend Services");
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        Label content = new Label(message == null ? "Unknown error" : message);
        content.setWrapText(true);
        content.setMaxWidth(440);
        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }
}
