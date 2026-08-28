package com.photostat.ui;

import com.photostat.services.ConfigService;
import com.photostat.services.DockerInstallService;
import com.photostat.services.DockerService;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-run wizard that gets the Docker backend services running.
 *
 * <p>Walks through checking for Docker, starting the engine, choosing a CPU or
 * GPU profile and which services to run, then downloading the images and
 * starting the containers.
 *
 * <p>Installing Docker is a machine-wide change, so the Docker step states
 * plainly what will be altered and requires an explicit tick before the install
 * button becomes available. Nothing here restarts the machine.
 *
 * <p>Every Docker call blocks, so all of them run on daemon threads and post
 * back with {@link Platform#runLater}.
 */
public class SetupWizardDialog extends Dialog<Boolean> {

    private enum Step {
        WELCOME("Welcome"),
        DOCKER("Docker"),
        ENGINE("Docker engine"),
        PROFILE("Services"),
        RUN("Download and start"),
        DONE("Finished");

        final String title;

        Step(String title) {
            this.title = title;
        }
    }

    private static final ButtonType BACK = new ButtonType("Back", ButtonBar.ButtonData.BACK_PREVIOUS);
    private static final ButtonType NEXT = new ButtonType("Next", ButtonBar.ButtonData.NEXT_FORWARD);
    private static final ButtonType FINISH = new ButtonType("Finish", ButtonBar.ButtonData.FINISH);

    private final ConfigService configService;
    private final DockerService dockerService;
    private final DockerInstallService installService;

    private Step step = Step.WELCOME;
    private final VBox content = new VBox(12);

    /** Set when the user ticks "Don't show this again" on the welcome step. */
    private boolean suppressFuture = false;

    /** Latest engine probe, refreshed whenever the Docker or engine step is shown. */
    private DockerService.EngineState engineState = DockerService.EngineState.NOT_INSTALLED;

    /**
     * False until the first probe returns. The Docker and engine steps render a
     * neutral "checking..." state until then, so a user who already has Docker
     * is never shown the install flow.
     */
    private boolean engineProbed = false;

    /** Per-service opt-in, opensearch always on. */
    private final Map<String, CheckBox> serviceChecks = new LinkedHashMap<>();
    private RadioButton cpuRadio;
    private RadioButton gpuRadio;

    private TextArea runLog;
    private boolean runSucceeded = false;
    private boolean busy = false;

    public SetupWizardDialog() {
        this.configService = ConfigService.getInstance();
        this.dockerService = DockerService.getInstance();
        this.installService = DockerInstallService.getInstance();

        setTitle("PhotoStat Backend Setup");
        setResizable(true);
        getDialogPane().setMinWidth(620);
        getDialogPane().setMinHeight(460);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().setAll(BACK, NEXT, FINISH, ButtonType.CANCEL);

        content.setPadding(new Insets(15));

        // Intercept Back/Next so they navigate instead of closing the dialog.
        wireNavigation(BACK, this::goBack);
        wireNavigation(NEXT, this::goNext);

        setResultConverter(button -> {
            if (button != null && button.getButtonData() == ButtonBar.ButtonData.FINISH) {
                persistChoices(true);
                return true;
            }
            // Cancelled: only stop asking if the user explicitly said so.
            if (suppressFuture) {
                persistChoices(false);
            }
            return false;
        });

        showStep(Step.WELCOME);
    }

    private void wireNavigation(ButtonType type, Runnable action) {
        Button button = (Button) getDialogPane().lookupButton(type);
        button.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            action.run();
        });
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void goNext() {
        Step[] steps = Step.values();
        int index = step.ordinal();
        if (index < steps.length - 1) {
            showStep(steps[index + 1]);
        }
    }

    private void goBack() {
        int index = step.ordinal();
        if (index > 0) {
            showStep(Step.values()[index - 1]);
        }
    }

    private void showStep(Step target) {
        step = target;
        setHeaderText(target.title);
        rebuildContent();

        if (target == Step.DOCKER || target == Step.ENGINE) {
            // Re-probe on entry; the user may have installed or started Docker
            // since the dialog opened.
            engineProbed = false;
            rebuildContent();
            probeEngine();
        }
    }

    /** Re-render the current step from current state. Never triggers a probe. */
    private void rebuildContent() {
        content.getChildren().setAll(buildStep(step));
        updateButtons();
    }

    /** Enable navigation according to what the current step still needs. */
    private void updateButtons() {
        Button back = (Button) getDialogPane().lookupButton(BACK);
        Button next = (Button) getDialogPane().lookupButton(NEXT);
        Button finish = (Button) getDialogPane().lookupButton(FINISH);

        back.setDisable(busy || step == Step.WELCOME);
        finish.setVisible(step == Step.DONE);
        finish.setManaged(step == Step.DONE);
        next.setVisible(step != Step.DONE);
        next.setManaged(step != Step.DONE);

        boolean canAdvance;
        switch (step) {
            case DOCKER:
                canAdvance = engineState != DockerService.EngineState.NOT_INSTALLED;
                break;
            case ENGINE:
                canAdvance = engineState == DockerService.EngineState.RUNNING;
                break;
            case RUN:
                canAdvance = runSucceeded;
                break;
            default:
                canAdvance = true;
        }
        next.setDisable(busy || !canAdvance);
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private List<Node> buildStep(Step target) {
        switch (target) {
            case WELCOME: return buildWelcome();
            case DOCKER: return buildDockerStep();
            case ENGINE: return buildEngineStep();
            case PROFILE: return buildProfileStep();
            case RUN: return buildRunStep();
            default: return buildDoneStep();
        }
    }

    private List<Node> buildWelcome() {
        Label intro = wrapped(
                "PhotoStat can run its heavier features in Docker containers, so you don't have "
                        + "to install Python, PyTorch or InsightFace by hand.");

        Label services = wrapped(
                "•  OpenSearch — the search index PhotoStat stores your photo metadata in. Required.\n"
                        + "•  Faces — face detection and clustering.\n"
                        + "•  Analysis — local tagging and captioning.\n"
                        + "•  Aesthetic — image-quality scoring.");

        Label optional = wrapped(
                "All of this is optional. PhotoStat also works against an OpenSearch server you "
                        + "already run, with the AI features in local Python mode or turned off. You "
                        + "can close this and set things up later from the Services tab.");

        Label privacy = wrapped(
                "The containers never get access to your photo folders — images are sent to them "
                        + "over a local connection, and every port is bound to this machine only.");
        privacy.getStyleClass().add("info-label-small");

        CheckBox dontAsk = new CheckBox("Don't show this again");
        dontAsk.setSelected(suppressFuture);
        dontAsk.selectedProperty().addListener((obs, was, is) -> suppressFuture = is);

        return List.of(intro, services, optional, new Separator(), privacy, dontAsk);
    }

    private List<Node> buildDockerStep() {
        List<Node> nodes = new ArrayList<>();

        if (!engineProbed) {
            Label checking = new Label("Checking for Docker...");
            checking.setStyle("-fx-font-weight: bold;");
            nodes.add(checking);
            return nodes;
        }

        boolean installed = engineState != DockerService.EngineState.NOT_INSTALLED;
        Label status = new Label(installed ? "Docker is installed" : "Docker was not found");
        status.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (installed ? "green" : "red") + ";");
        nodes.add(status);

        if (installed) {
            nodes.add(wrapped("Nothing to do here — click Next."));
            return nodes;
        }

        DockerInstallService.InstallMethod method = installService.detectInstallMethod();

        nodes.add(wrapped("Docker was not found on this machine. Installing Docker Desktop "
                + "changes system-wide settings, so please read what it does first:"));

        VBox changes = new VBox(6);
        for (String change : installService.describeSystemChanges()) {
            changes.getChildren().add(wrapped("•  " + change));
        }
        changes.setPadding(new Insets(0, 0, 0, 10));
        nodes.add(changes);

        List<String> conflicts = installService.detectHypervisorConflicts();
        if (!conflicts.isEmpty()) {
            Label warning = wrapped("Heads up: " + String.join(" and ", conflicts)
                    + " is installed. WSL 2 runs Windows under a hypervisor, which can slow down "
                    + "or break other virtualisation software. Older versions are affected worst.");
            warning.setStyle("-fx-text-fill: darkorange;");
            nodes.add(warning);
        }

        Hyperlink licence = new Hyperlink("Read Docker Desktop's licence terms");
        licence.setOnAction(e -> installService.openInBrowser(DockerInstallService.LICENCE_URL));
        nodes.add(licence);

        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(6);

        Button installButton = new Button();
        CheckBox consent = new CheckBox("I understand these changes and want to install Docker Desktop");

        if (method == DockerInstallService.InstallMethod.MANUAL) {
            installButton.setText("Open the Docker download page");
            installButton.setOnAction(e -> installService.openInBrowser(DockerInstallService.DOWNLOAD_URL));
            nodes.add(wrapped("No supported package manager was found here, so Docker has to be "
                    + "installed manually. Once it is installed, click Re-check."));
            nodes.add(installButton);
        } else {
            installButton.setText(method == DockerInstallService.InstallMethod.WINGET
                    ? "Install with winget" : "Install with Homebrew");
            installButton.setDisable(true);
            consent.selectedProperty().addListener((obs, was, is) -> installButton.setDisable(!is));
            installButton.setOnAction(e -> runInstall(log));
            nodes.add(consent);
            nodes.add(new HBox(10, installButton));
        }

        Button recheck = new Button("Re-check");
        recheck.setOnAction(e -> probeEngine());
        nodes.add(new HBox(10, recheck));
        nodes.add(log);

        return nodes;
    }

    private List<Node> buildEngineStep() {
        List<Node> nodes = new ArrayList<>();

        if (!engineProbed) {
            Label checking = new Label("Checking the Docker engine...");
            checking.setStyle("-fx-font-weight: bold;");
            nodes.add(checking);
            return nodes;
        }

        boolean running = engineState == DockerService.EngineState.RUNNING;
        Label status = new Label(running
                ? "The Docker engine is running"
                : "The Docker engine is not running");
        status.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                + (running ? "green" : "darkorange") + ";");
        nodes.add(status);

        if (running) {
            nodes.add(wrapped("Nothing to do here — click Next."));
            return nodes;
        }

        nodes.add(wrapped("The Docker engine has to be running before the backend services can "
                + "start. A cold start usually takes 30 to 60 seconds, and Docker Desktop may ask "
                + "you to accept its own terms the first time."));

        Button start = new Button("Start Docker");
        start.setOnAction(e -> startEngine());
        nodes.add(new HBox(10, start));

        nodes.add(wrapped("If it does not start, Docker Desktop may still need a restart to finish "
                + "installing. PhotoStat will not restart your machine — restart when it suits "
                + "you and reopen this wizard from the Services tab."));

        return nodes;
    }

    private List<Node> buildProfileStep() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(wrapped("Choose which services to run. You can change any of this later from "
                + "the Services tab."));

        ToggleGroup group = new ToggleGroup();
        cpuRadio = new RadioButton("CPU (works everywhere)");
        gpuRadio = new RadioButton("GPU (NVIDIA only — much faster, much larger downloads)");
        cpuRadio.setToggleGroup(group);
        gpuRadio.setToggleGroup(group);
        if (configService.isDockerGpu()) {
            gpuRadio.setSelected(true);
        } else {
            cpuRadio.setSelected(true);
        }

        nodes.add(new Label("Hardware profile"));
        nodes.add(new VBox(6, cpuRadio, gpuRadio));
        Label gpuNote = wrapped("GPU mode needs recent NVIDIA drivers and the NVIDIA Container "
                + "Toolkit. On Windows that works through WSL 2.");
        gpuNote.getStyleClass().add("info-label-small");
        nodes.add(gpuNote);

        nodes.add(new Separator());
        nodes.add(new Label("Services"));

        List<String> enabled = configService.getDockerServices();
        serviceChecks.clear();
        VBox box = new VBox(6);
        for (String service : DockerService.ALL_SERVICES) {
            CheckBox check = new CheckBox(service);
            boolean required = DockerService.SERVICE_OPENSEARCH.equals(service);
            check.setSelected(required || enabled.contains(service));
            check.setDisable(required);
            if (required) {
                check.setText(service + "  (required)");
            }
            serviceChecks.put(service, check);
            box.getChildren().add(check);
        }
        nodes.add(box);

        Label sizeNote = wrapped("Each AI service downloads a multi-gigabyte image the first time "
                + "it starts. Leave out anything you don't plan to use — you can add it later.");
        sizeNote.getStyleClass().add("info-label-small");
        nodes.add(sizeNote);

        return nodes;
    }

    private List<Node> buildRunStep() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(wrapped("PhotoStat will download the container images and start the services "
                + "you selected. The first download is large and can take a while; you can leave "
                + "this running."));

        runLog = new TextArea();
        runLog.setEditable(false);
        runLog.setPrefRowCount(12);

        Button start = new Button("Download and start");
        start.setId("run-setup");
        Button cancel = new Button("Cancel");
        cancel.setDisable(true);

        start.setOnAction(e -> {
            start.setDisable(true);
            cancel.setDisable(false);
            runSetup(() -> {
                start.setDisable(false);
                cancel.setDisable(true);
            });
        });
        cancel.setOnAction(e -> dockerService.cancel());

        nodes.add(new HBox(10, start, cancel));
        nodes.add(runLog);
        return nodes;
    }

    private List<Node> buildDoneStep() {
        List<Node> nodes = new ArrayList<>();
        Label done = new Label("Setup complete");
        done.setStyle("-fx-font-weight: bold; -fx-text-fill: green;");
        nodes.add(done);
        nodes.add(wrapped("The backend services are running. You can start and stop them any time "
                + "from the Services tab."));
        nodes.add(wrapped("The AI services download their model weights the first time they are "
                + "used, so the first face scan or analysis run may be slow while that happens."));
        nodes.add(wrapped("To remove all of this later, stop the services from the Services tab and "
                + "uninstall Docker Desktop the usual way for your platform."));
        return nodes;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void probeEngine() {
        setBusy(true);
        runInBackground("wizard-probe", () -> {
            DockerService.EngineState state = dockerService.getEngineState();
            Platform.runLater(() -> {
                engineState = state;
                engineProbed = true;
                // The Docker and engine steps branch entirely on this result, so
                // re-render rather than patching individual labels.
                if (step == Step.DOCKER || step == Step.ENGINE) {
                    rebuildContent();
                }
                setBusy(false);
            });
        });
    }

    private void runInstall(TextArea log) {
        setBusy(true);
        appendTo(log, "Starting the Docker Desktop installation...");
        runInBackground("wizard-install", () -> {
            String error = installService.installDockerDesktop(line -> appendTo(log, line));
            Platform.runLater(() -> {
                if (error != null) {
                    appendTo(log, error);
                } else {
                    appendTo(log, "Installation finished. Checking for Docker...");
                }
                setBusy(false);
                probeEngine();
            });
        });
    }

    private void startEngine() {
        setBusy(true);
        runInBackground("wizard-engine", () -> {
            dockerService.startEngine(progress -> { /* status shown via probe */ });
            Platform.runLater(() -> {
                setBusy(false);
                probeEngine();
            });
        });
    }

    private void runSetup(Runnable onFinished) {
        // Apply the profile before pulling so the GPU overlay is picked up.
        persistChoices(false);

        List<String> services = selectedServices();
        setBusy(true);
        appendTo(runLog, "Pulling images for: " + String.join(", ", services));

        runInBackground("wizard-run", () -> {
            DockerService.CommandResult pull =
                    dockerService.composePull(services, p -> appendTo(runLog, p.getStatusLine()));
            if (!pull.isSuccess()) {
                Platform.runLater(() -> {
                    appendTo(runLog, "Could not download the images: " + pull.getError());
                    setBusy(false);
                    onFinished.run();
                });
                return;
            }

            appendTo(runLog, "Starting services...");
            DockerService.CommandResult up =
                    dockerService.composeUp(services, p -> appendTo(runLog, p.getStatusLine()));

            Platform.runLater(() -> {
                if (up.isSuccess()) {
                    runSucceeded = true;
                    appendTo(runLog, "Services started. Click Next to finish.");
                } else {
                    appendTo(runLog, "Could not start the services: " + up.getError());
                }
                setBusy(false);
                onFinished.run();
            });
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> selectedServices() {
        List<String> services = new ArrayList<>();
        for (Map.Entry<String, CheckBox> entry : serviceChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                services.add(entry.getKey());
            }
        }
        if (services.isEmpty()) {
            services.add(DockerService.SERVICE_OPENSEARCH);
        }
        return services;
    }

    /**
     * Write the wizard's choices to the config.
     *
     * @param completed true to stop the wizard appearing on future launches
     */
    private void persistChoices(boolean completed) {
        if (gpuRadio != null) {
            configService.setDockerGpu(gpuRadio.isSelected());
        }
        if (!serviceChecks.isEmpty()) {
            configService.setDockerServices(selectedServices());
        }
        if (completed || suppressFuture) {
            configService.setDockerSetupCompleted(true);
        }
        configService.saveConfig();
    }

    private void setBusy(boolean value) {
        busy = value;
        Runnable apply = () -> {
            getDialogPane().setCursor(value ? Cursor.WAIT : Cursor.DEFAULT);
            updateButtons();
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    private void appendTo(TextArea area, String line) {
        if (area == null || line == null || line.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            area.appendText(line + "\n");
            if (area.getLength() > 200_000) {
                area.deleteText(0, 100_000);
            }
        });
    }

    private void runInBackground(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    private Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(560);
        return label;
    }
}
