package com.photostat.ui;

import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.OpenSearchService.GpsClusterData;
import com.photostat.services.OpenSearchService.GpsImageData;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Panel displaying an interactive map of geotagged photos using Leaflet.js in a WebView.
 * Uses two modes: cluster mode (zoomed out) and marker mode (zoomed in).
 */
public class MapPanel extends BorderPane {

    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;
    private final LoggingService logger;

    private WebView webView;
    private WebEngine webEngine;
    private Label statusLabel;

    private boolean mapLoaded = false;
    private boolean dataLoadPending = false;
    private boolean initialFitDone = false;

    // Strong reference to prevent GC — WebView only holds a weak reference to bridge objects
    private JavaBridge javaBridge;

    public MapPanel() {
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.logger = LoggingService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setPadding(new Insets(0));

        webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);

        // Set up load listener to register Java bridge once page loads
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                javaBridge = new JavaBridge();
                window.setMember("javaBridge", javaBridge);
                mapLoaded = true;
                logger.info("MapPanel", "Map loaded, Java bridge registered");

                if (dataLoadPending) {
                    dataLoadPending = false;
                    loadInitialData();
                }
            }
        });

        // Status bar
        statusLabel = new Label("Map: loading...");
        statusLabel.setPadding(new Insets(4, 8, 4, 8));
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        setCenter(webView);
        setBottom(statusLabel);
    }

    /**
     * Refresh the map view. Called when the Map tab is selected.
     */
    public void refresh() {
        if (!openSearchService.isConnected()) {
            statusLabel.setText("Map: not connected to OpenSearch");
            return;
        }

        if (!mapLoaded) {
            // Copy map.html to ~/.photostat/ so it loads from file:// origin,
            // allowing WebView to display file:// thumbnail URLs in popups
            dataLoadPending = true;
            try {
                Path mapFile = Path.of(System.getProperty("user.home"), ".photostat", "map.html");
                try (InputStream is = getClass().getResourceAsStream("/map.html")) {
                    Files.copy(is, mapFile, StandardCopyOption.REPLACE_EXISTING);
                }
                webEngine.load(mapFile.toUri().toString());
            } catch (Exception e) {
                logger.error("MapPanel", "Failed to copy map.html, falling back to resource URL", e);
                webEngine.load(getClass().getResource("/map.html").toExternalForm());
            }
        } else {
            loadInitialData();
        }
    }

    private void loadInitialData() {
        initialFitDone = false;
        Thread thread = new Thread(() -> {
            try {
                long gpsCount = openSearchService.countGpsImages();

                // Fetch global clusters to fit map to data bounds
                List<GpsClusterData> clusters = openSearchService.searchGpsClusters(3, null);

                Platform.runLater(() -> {
                    statusLabel.setText("Map: " + gpsCount + " geotagged images");
                    if (gpsCount == 0) {
                        webEngine.executeScript("showEmptyMessage()");
                    } else {
                        webEngine.executeScript("hideEmptyMessage()");
                        if (!clusters.isEmpty()) {
                            // Build bounds array and fit map to data
                            StringBuilder boundsJson = new StringBuilder("[");
                            for (int i = 0; i < clusters.size(); i++) {
                                if (i > 0) boundsJson.append(",");
                                boundsJson.append("[").append(clusters.get(i).getLatitude())
                                        .append(",").append(clusters.get(i).getLongitude()).append("]");
                            }
                            boundsJson.append("]");
                            initialFitDone = true;
                            webEngine.executeScript("fitToData(" + boundsJson + ")");
                        } else {
                            webEngine.executeScript("notifyJava()");
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("MapPanel", "Failed to load initial GPS data", e);
                Platform.runLater(() -> statusLabel.setText("Map: error loading GPS data"));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Map zoom level to geotile aggregation precision.
     */
    private int zoomToPrecision(int zoom) {
        if (zoom <= 3) return 3;
        if (zoom <= 5) return 5;
        if (zoom <= 8) return 7;
        if (zoom <= 10) return 9;
        return 11;
    }

    private void loadClusters(int zoom, double south, double west, double north, double east) {
        Platform.runLater(() -> webEngine.executeScript("setLoadingMessage('Loading clusters...')"));

        Thread thread = new Thread(() -> {
            try {
                int precision = zoomToPrecision(zoom);
                double[] bounds = {south, west, north, east};
                List<GpsClusterData> clusters = openSearchService.searchGpsClusters(precision, bounds);

                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < clusters.size(); i++) {
                    GpsClusterData c = clusters.get(i);
                    if (i > 0) json.append(",");
                    json.append("{\"lat\":").append(c.getLatitude())
                        .append(",\"lon\":").append(c.getLongitude())
                        .append(",\"count\":").append(c.getCount())
                        .append("}");
                }
                json.append("]");

                String jsonStr = json.toString();
                Platform.runLater(() -> {
                    webEngine.executeScript("addClusters(" + jsonStr + ")");
                    statusLabel.setText("Map: " + clusters.size() + " clusters (zoom " + zoom + ")");
                });
            } catch (Exception e) {
                logger.error("MapPanel", "Failed to load clusters", e);
                Platform.runLater(() -> {
                    webEngine.executeScript("hideLoading()");
                    statusLabel.setText("Map: error loading clusters");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void loadMarkers(int zoom, double south, double west, double north, double east) {
        Platform.runLater(() -> webEngine.executeScript("setLoadingMessage('Loading photos...')"));

        Thread thread = new Thread(() -> {
            try {
                double[] bounds = {south, west, north, east};
                List<GpsImageData> images = openSearchService.searchGpsImages(bounds, 500);

                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < images.size(); i++) {
                    GpsImageData img = images.get(i);
                    if (i > 0) json.append(",");
                    json.append("{\"lat\":").append(img.getLatitude())
                        .append(",\"lon\":").append(img.getLongitude())
                        .append(",\"fileName\":\"").append(escapeJson(img.getFileName())).append("\"");

                    if (img.getDateTaken() != null) {
                        json.append(",\"date\":\"").append(escapeJson(img.getDateTaken())).append("\"");
                    }
                    if (img.getCameraModel() != null) {
                        json.append(",\"camera\":\"").append(escapeJson(img.getCameraModel())).append("\"");
                    }

                    // Check for cached thumbnail
                    String thumbUrl = getThumbnailUrl(img.getFilePath());
                    if (thumbUrl != null) {
                        json.append(",\"thumb\":\"").append(escapeJson(thumbUrl)).append("\"");
                    }

                    json.append("}");
                }
                json.append("]");

                String jsonStr = json.toString();
                Platform.runLater(() -> {
                    webEngine.executeScript("addMarkers(" + jsonStr + ")");
                    statusLabel.setText("Map: " + images.size() + " photos in view (zoom " + zoom + ")");
                });
            } catch (Exception e) {
                logger.error("MapPanel", "Failed to load markers", e);
                Platform.runLater(() -> {
                    webEngine.executeScript("hideLoading()");
                    statusLabel.setText("Map: error loading photos");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Get a file:// URL for a cached thumbnail, or null if not cached.
     */
    private String getThumbnailUrl(String filePath) {
        try {
            Path cachePath = thumbnailService.getDiskCachePath(filePath);
            if (Files.exists(cachePath)) {
                return cachePath.toUri().toString();
            }
        } catch (Exception e) {
            // Ignore — no thumbnail available
        }
        return null;
    }

    /**
     * Escape a string for safe inclusion in a JSON string value.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Java bridge object exposed to JavaScript for map event callbacks.
     */
    public class JavaBridge {
        public void onMapMoved(int zoom, double south, double west, double north, double east) {
            logger.debug("MapPanel", "Map moved: zoom=" + zoom +
                    " bounds=[" + south + "," + west + "," + north + "," + east + "]");

            if (zoom > 12) {
                loadMarkers(zoom, south, west, north, east);
            } else {
                loadClusters(zoom, south, west, north, east);
            }
        }
    }
}
