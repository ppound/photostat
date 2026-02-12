package com.photostat;

import com.photostat.services.ConfigService;
import com.photostat.ui.MainWindow;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
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

    private static final String APP_TITLE = "PhotoStat - Image Metadata Indexer";
    private static Scene mainScene;

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

        Scene scene = new Scene(mainWindow, width, height);
        mainScene = scene;

        // Load stylesheet based on theme setting
        applyTheme(scene, configService.getTheme());

        // Configure stage
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);

        // Center window on screen
        primaryStage.setX((screenBounds.getWidth() - width) / 2 + screenBounds.getMinX());
        primaryStage.setY((screenBounds.getHeight() - height) / 2 + screenBounds.getMinY());

        // Save window size on close
        primaryStage.setOnCloseRequest(event -> {
            configService.setWindowWidth((int) primaryStage.getWidth());
            configService.setWindowHeight((int) primaryStage.getHeight());
            configService.saveConfig();
        });

        primaryStage.show();
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
