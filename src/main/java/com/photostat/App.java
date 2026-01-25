package com.photostat;

import com.photostat.services.ConfigService;
import com.photostat.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
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

    @Override
    public void start(Stage primaryStage) {
        // Initialize configuration
        ConfigService configService = ConfigService.getInstance();

        // Create main window
        MainWindow mainWindow = new MainWindow();

        // Create scene with configured dimensions
        int width = configService.getWindowWidth();
        int height = configService.getWindowHeight();
        Scene scene = new Scene(mainWindow, width, height);

        // Load stylesheet
        try {
            String css = getClass().getResource("/styles.css").toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            System.err.println("Could not load stylesheet: " + e.getMessage());
        }

        // Configure stage
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);

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

    public static void main(String[] args) {
        // Print startup info
        System.out.println("Starting PhotoStat Java...");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("JavaFX version: " + System.getProperty("javafx.version"));

        launch(args);
    }
}
