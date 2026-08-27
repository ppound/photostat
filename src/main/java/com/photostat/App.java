package com.photostat;

import com.photostat.services.ConfigService;
import com.photostat.services.DockerService;
import com.photostat.ui.MainWindow;
import com.photostat.ui.SetupWizardDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Main application entry point for PhotoStat Java.
 *
 * PhotoStat is a desktop application for indexing and searching image EXIF metadata
 * using OpenSearch. It provides thumbnail display, charts/visualizations, faceted search,
 * and configurable directory indexing.
 */
public class App extends Application {

    private static final String APP_TITLE;
    static {
        String version = App.class.getPackage().getImplementationVersion();
        APP_TITLE = version != null
                ? "PhotoStat " + version + " - Image Metadata Indexer"
                : "PhotoStat - Image Metadata Indexer";
    }
    private static Scene mainScene;

    /** Guards against re-entering the shutdown path once a stop is under way. */
    private boolean stoppingBackends = false;

    @Override
    public void start(Stage primaryStage) {
        // Initialize configuration
        ConfigService configService = ConfigService.getInstance();

        // Create main window
        MainWindow mainWindow = new MainWindow();

        // Get screen dimensions and calculate 80% size
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        int defaultWidth = (int) (screenBounds.getWidth() * 0.8);
        int defaultHeight = (int) (screenBounds.getHeight() * 0.8);

        // Use saved dimensions if available, otherwise use 80% of screen
        int width = configService.getWindowWidth();
        int height = configService.getWindowHeight();

        // If saved size is the old default (1200x800), use new 80% default
        if (width == 1200 && height == 800) {
            width = defaultWidth;
            height = defaultHeight;
        }

        // Clamp to current screen so the window never extends beyond the visible area
        width = Math.min(width, (int) screenBounds.getWidth());
        height = Math.min(height, (int) screenBounds.getHeight());

        Scene scene = new Scene(mainWindow, width, height);
        mainScene = scene;

        // Load stylesheet based on theme setting
        applyTheme(scene, configService.getTheme());

        // Configure stage
        primaryStage.setTitle(APP_TITLE);
        try {
            primaryStage.getIcons().add(new Image(App.class.getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            System.err.println("Could not load app icon: " + e.getMessage());
        }
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);

        // Center window on screen, ensuring it stays within visible bounds
        double x = (screenBounds.getWidth() - width) / 2 + screenBounds.getMinX();
        double y = (screenBounds.getHeight() - height) / 2 + screenBounds.getMinY();
        primaryStage.setX(Math.max(x, screenBounds.getMinX()));
        primaryStage.setY(Math.max(y, screenBounds.getMinY()));

        // Save scene (content) size on close — not stage size, which includes
        // window decorations and would grow the window on every launch cycle
        primaryStage.setOnCloseRequest(event -> {
            configService.setWindowWidth((int) scene.getWidth());
            configService.setWindowHeight((int) scene.getHeight());
            configService.saveConfig();

            // Stopping containers takes long enough to need a progress window,
            // so cancel this close and exit once the stop finishes.
            if (shouldStopBackendsOnExit(configService) && !stoppingBackends) {
                stoppingBackends = true;
                event.consume();
                stopBackendsThenExit(primaryStage);
            }
        });

        primaryStage.show();

        maybeShowSetupWizard(configService);
        maybeAutoStartBackends(configService);
    }

    private boolean shouldStopBackendsOnExit(ConfigService configService) {
        return configService.isDockerManageContainers() && configService.isDockerStopOnExit();
    }

    /**
     * Start the backend containers in the background when the user has opted in.
     *
     * <p>Also starts the Docker engine if it is not already running, since
     * otherwise the setting would do nothing on a machine where Docker Desktop
     * is not set to launch at login. Failures are logged rather than surfaced:
     * the app is fully usable without the containers, and an error dialog on
     * every launch would be worse than a quiet retry from the Services tab.
     */
    private void maybeAutoStartBackends(ConfigService configService) {
        if (!configService.isDockerManageContainers() || !configService.isDockerAutoStartOnLaunch()) {
            return;
        }

        Thread thread = new Thread(() -> {
            DockerService docker = DockerService.getInstance();
            if (!docker.isComposeFileAvailable()) {
                System.err.println("Auto-start skipped: no compose file at " + docker.getComposeFile());
                return;
            }
            if (!docker.isDaemonRunning()) {
                String error = docker.startEngine(null);
                if (error != null) {
                    System.err.println("Auto-start skipped: " + error);
                    return;
                }
            }
            DockerService.CommandResult result =
                    docker.composeUp(configService.getDockerServices(), null);
            if (!result.isSuccess()) {
                System.err.println("Auto-start failed: " + result.getError());
            }
        }, "docker-auto-start");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stop the backend containers, then exit.
     *
     * <p>Shows a small progress window because {@code compose stop} sends
     * SIGTERM and waits before killing, which can take tens of seconds across
     * four containers. "Close anyway" abandons the wait — the containers keep
     * running, which is harmless.
     */
    private void stopBackendsThenExit(Stage primaryStage) {
        Label message = new Label("Stopping the backend services...");
        ProgressBar progress = new ProgressBar();
        progress.setPrefWidth(280);

        Button closeAnyway = new Button("Close anyway");
        closeAnyway.setOnAction(e -> Platform.exit());

        VBox box = new VBox(12, message, progress, closeAnyway);
        box.setPadding(new Insets(20));

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Shutting down");
        dialog.setScene(new Scene(box));
        dialog.setOnCloseRequest(Event::consume);
        dialog.show();

        Thread thread = new Thread(() -> {
            DockerService docker = DockerService.getInstance();
            if (docker.isDaemonRunning()) {
                docker.composeStop(null, null);
            }
            Platform.runLater(() -> {
                dialog.close();
                Platform.exit();
            });
        }, "docker-stop-on-exit");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Offer the Docker backend setup wizard on first run.
     *
     * <p>Shown after the main window so PhotoStat is usable behind it, and only
     * when the user has not already completed or dismissed it. Everything the
     * wizard sets up is optional, so declining leaves a working app.
     */
    private void maybeShowSetupWizard(ConfigService configService) {
        if (configService.isDockerSetupCompleted() || !configService.isDockerManageContainers()) {
            return;
        }
        // Defer so the main window paints first.
        Platform.runLater(() -> {
            try {
                new SetupWizardDialog().showAndWait();
            } catch (Exception e) {
                System.err.println("Setup wizard failed to open: " + e.getMessage());
            }
        });
    }

    @Override
    public void stop() {
        // Cleanup on application exit
        System.out.println("PhotoStat shutting down...");
    }

    public static Scene getMainScene() {
        return mainScene;
    }

    public static void applyTheme(Scene scene, String theme) {
        scene.getStylesheets().clear();
        try {
            String cssFile = "dark".equalsIgnoreCase(theme) ? "/styles-dark.css" : "/styles.css";
            String css = App.class.getResource(cssFile).toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            System.err.println("Could not load stylesheet: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Print startup info
        System.out.println("Starting PhotoStat Java...");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("JavaFX version: " + System.getProperty("javafx.version"));

        launch(args);
    }
}
