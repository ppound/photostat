package com.photostat.ui;

import com.photostat.App;
import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.ImageAnalysisService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.RcloneService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dialog for configuring application settings.
 */
public class SettingsDialog extends Dialog<Boolean> {

    private final ConfigService configService;
    private final OpenSearchService openSearchService;

    private TextField hostField;
    private TextField portField;
    private CheckBox sslCheckbox;
    private TextField usernameField;
    private PasswordField passwordField;
    private TextField indexNameField;
    private TextField exifToolPathField;
    private Spinner<Integer> batchSizeSpinner;
    private ComboBox<String> sidecarFormatCombo;
    private CheckBox sidecarReadBothCheckbox;
    private ComboBox<String> themeCombo;
    private Spinner<Integer> thumbnailSizeSpinner;
    private Spinner<Integer> resultsPerPageSpinner;
    private ComboBox<String> openTabCombo;

    private static final String OPEN_TAB_SEARCH_LABEL = "Search";
    private static final String OPEN_TAB_ONTHEDAY_LABEL = "On This Day";
    private static final String OPEN_TAB_LASTUSED_LABEL = "Last used";

    // Logging settings
    private CheckBox loggingEnabledCheckbox;
    private ComboBox<String> logLevelCombo;

    // Cache settings
    private CheckBox cacheEnabledCheckbox;
    private Spinner<Integer> cacheMaxSizeSpinner;
    private Label cacheStatsLabel;

    // File extension checkboxes
    private final Map<String, CheckBox> extensionCheckboxes = new LinkedHashMap<>();

    // AI Provider settings
    private ComboBox<String> aiProviderCombo;

    // Claude API settings
    private PasswordField claudeApiKeyField;
    private ComboBox<String> claudeModelCombo;

    // Gemini API settings
    private PasswordField geminiApiKeyField;
    private ComboBox<String> geminiModelCombo;

    // Ollama / OpenAI-compatible local API settings
    private TextField ollamaBaseUrlField;
    private PasswordField ollamaApiKeyField;
    private ComboBox<String> ollamaModelCombo;

    // Moondream settings
    private TextField moondreamPythonPathField;
    private ComboBox<String> moondreamModelCombo;
    private Label moondreamStatusLabel;
    private ComboBox<String> moondreamModeCombo;
    private TextField moondreamEndpointField;

    // Analysis prompt
    private TextArea analysisPromptArea;

    // Face recognition settings
    private CheckBox facesEnabledCheckbox;
    private TextField facesPythonPathField;
    private Slider facesConfidenceSlider;
    private Slider facesClusterSlider;
    private Label facesPythonStatusLabel;
    private ComboBox<String> facesModeCombo;
    private TextField facesEndpointField;

    // Backend mode combo labels (shared by faces + moondream).
    private static final String MODE_LOCAL_LABEL = "Local (Python)";
    private static final String MODE_DOCKER_LABEL = "Docker (HTTP)";

    // Luma AI image generation settings
    private PasswordField lumaApiKeyField;
    private PasswordField imgbbApiKeyField;
    private TextField lumaOutputDirField;
    private ComboBox<String> lumaAspectRatioCombo;
    private ComboBox<String> lumaRefTypeCombo;
    private Slider lumaRefWeightSlider;

    // rclone cloud upload settings
    private TextField rclonePathField;
    private TextField rcloneRemoteNameField;
    private TextField rcloneRemotePathField;
    private ListView<String> rcloneUploadDirsListView;

    private Label connectionStatusLabel;

    public SettingsDialog() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();

        setTitle("Settings");
        setHeaderText("Configure PhotoStat Settings");
        setResizable(true);

        // Dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Create tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab openSearchTab = new Tab("OpenSearch");
        openSearchTab.setContent(createOpenSearchPane());

        Tab indexingTab = new Tab("Indexing");
        indexingTab.setContent(createIndexingPane());

        Tab uiTab = new Tab("User Interface");
        uiTab.setContent(createUIPane());

        Tab loggingTab = new Tab("Logging");
        loggingTab.setContent(createLoggingPane());

        Tab cacheTab = new Tab("Cache");
        cacheTab.setContent(createCachePane());

        Tab aiTab = new Tab("AI Analysis");
        aiTab.setContent(createAIPane());

        Tab facesTab = new Tab("Face Recognition");
        facesTab.setContent(createFacesPane());

        Tab imageGenTab = new Tab("Image Generation");
        imageGenTab.setContent(createImageGenerationPane());

        Tab cloudUploadTab = new Tab("Cloud Upload");
        cloudUploadTab.setContent(createCloudUploadPane());

        tabPane.getTabs().addAll(openSearchTab, indexingTab, uiTab, loggingTab, cacheTab, aiTab, imageGenTab, facesTab, cloudUploadTab);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(450);
        content.setPrefHeight(350);
        content.getChildren().add(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getDialogPane().setContent(content);

        // Load current settings
        loadSettings();

        // Result converter
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                saveSettings();
                return true;
            }
            return false;
        });
    }

    private VBox createOpenSearchPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Host
        grid.add(new Label("Host:"), 0, row);
        hostField = new TextField();
        hostField.setPromptText("localhost");
        hostField.setPrefWidth(200);
        grid.add(hostField, 1, row++);

        // Port
        grid.add(new Label("Port:"), 0, row);
        portField = new TextField();
        portField.setPromptText("9200");
        portField.setPrefWidth(80);
        grid.add(portField, 1, row++);

        // SSL
        grid.add(new Label("Use SSL:"), 0, row);
        sslCheckbox = new CheckBox("Enable HTTPS");
        grid.add(sslCheckbox, 1, row++);

        // Username
        grid.add(new Label("Username:"), 0, row);
        usernameField = new TextField();
        usernameField.setPromptText("(optional)");
        grid.add(usernameField, 1, row++);

        // Password
        grid.add(new Label("Password:"), 0, row);
        passwordField = new PasswordField();
        passwordField.setPromptText("(optional)");
        grid.add(passwordField, 1, row++);

        // Index name
        grid.add(new Label("Index Name:"), 0, row);
        indexNameField = new TextField();
        indexNameField.setPromptText("photostat");
        grid.add(indexNameField, 1, row++);

        // Test connection button
        Button testButton = new Button("Test Connection");
        testButton.setOnAction(e -> testConnection());

        connectionStatusLabel = new Label("");
        connectionStatusLabel.setWrapText(true);

        HBox testBox = new HBox(10, testButton, connectionStatusLabel);
        HBox.setHgrow(connectionStatusLabel, Priority.ALWAYS);

        pane.getChildren().addAll(grid, new Separator(), testBox);

        return pane;
    }

    private VBox createIndexingPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Batch size
        grid.add(new Label("Batch Size:"), 0, row);
        batchSizeSpinner = new Spinner<>(10, 500, 50, 10);
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(100);
        grid.add(batchSizeSpinner, 1, row);
        grid.add(new Label("(documents per batch)"), 2, row++);

        // ExifTool path
        grid.add(new Label("ExifTool Path:"), 0, row);
        exifToolPathField = new TextField();
        exifToolPathField.setPromptText("exiftool");
        exifToolPathField.setPrefWidth(200);
        grid.add(exifToolPathField, 1, row);

        Button browseExifTool = new Button("...");
        browseExifTool.setOnAction(e -> browseForExifTool());
        grid.add(browseExifTool, 2, row++);

        // Sidecar format
        grid.add(new Label("Sidecar Format:"), 0, row);
        sidecarFormatCombo = new ComboBox<>();
        sidecarFormatCombo.getItems().addAll("JSON", "XMP", "Both");
        sidecarFormatCombo.setPrefWidth(120);
        sidecarFormatCombo.setTooltip(new Tooltip(
                "JSON (.photostat.json): PhotoStat's native format — simple, compact.\n" +
                "XMP (.xmp): industry standard readable by Lightroom, Bridge, digiKam,\n" +
                "ExifTool, and Windows/Mac tooling.\n" +
                "Both: write both formats during migration."));
        grid.add(sidecarFormatCombo, 1, row);
        grid.add(new Label("(custom metadata storage)"), 2, row++);

        // Read-both fallback
        grid.add(new Label(""), 0, row);
        sidecarReadBothCheckbox = new CheckBox("Read from either format if primary is missing");
        sidecarReadBothCheckbox.setTooltip(new Tooltip(
                "When enabled, PhotoStat reads whichever sidecar exists, making\n" +
                "migration between formats seamless."));
        grid.add(sidecarReadBothCheckbox, 1, row++, 2, 1);

        // File types to index
        Label fileTypesLabel = new Label("File Types to Index:");
        fileTypesLabel.setStyle("-fx-font-weight: bold;");

        FlowPane extensionsPane = new FlowPane(10, 5);
        String[] allExtensions = {
            ".jpg", ".jpeg", ".png", ".tiff", ".tif",
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf"
        };
        List<String> enabledExtensions = configService.getFileExtensions();
        for (String ext : allExtensions) {
            CheckBox cb = new CheckBox(ext);
            cb.setSelected(enabledExtensions.contains(ext));
            extensionCheckboxes.put(ext, cb);
            extensionsPane.getChildren().add(cb);
        }

        Label fileTypesInfoLabel = new Label(
                "TIFF and RAW file types (CR2, NEF, ARW, etc.) are slower to index and require ExifTool."
        );
        fileTypesInfoLabel.setWrapText(true);
        fileTypesInfoLabel.getStyleClass().add("info-label");

        // Info labels
        Label exifToolInfoLabel = new Label(
                "ExifTool is required for extracting metadata from RAW files (CR2, NEF, ARW, etc.). " +
                        "Download from: https://exiftool.org/"
        );
        exifToolInfoLabel.setWrapText(true);
        exifToolInfoLabel.getStyleClass().add("info-label");

        pane.getChildren().addAll(grid, new Separator(), fileTypesLabel, extensionsPane, fileTypesInfoLabel, new Separator(), exifToolInfoLabel);

        return pane;
    }

    private VBox createUIPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Theme
        grid.add(new Label("Theme:"), 0, row);
        themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll("Light", "Dark");
        themeCombo.setPrefWidth(100);
        grid.add(themeCombo, 1, row++);

        // Thumbnail size
        grid.add(new Label("Thumbnail Size:"), 0, row);
        thumbnailSizeSpinner = new Spinner<>(50, 400, 200, 25);
        thumbnailSizeSpinner.setEditable(true);
        thumbnailSizeSpinner.setPrefWidth(100);
        grid.add(thumbnailSizeSpinner, 1, row);
        grid.add(new Label("pixels"), 2, row++);

        // Results per page
        grid.add(new Label("Results Per Page:"), 0, row);
        resultsPerPageSpinner = new Spinner<>(10, 200, 50, 10);
        resultsPerPageSpinner.setEditable(true);
        resultsPerPageSpinner.setPrefWidth(100);
        grid.add(resultsPerPageSpinner, 1, row++);

        // Open to tab (which tab is selected on launch)
        grid.add(new Label("Open to tab:"), 0, row);
        openTabCombo = new ComboBox<>();
        openTabCombo.getItems().addAll(OPEN_TAB_SEARCH_LABEL, OPEN_TAB_ONTHEDAY_LABEL, OPEN_TAB_LASTUSED_LABEL);
        openTabCombo.setPrefWidth(150);
        grid.add(openTabCombo, 1, row);
        Label openTabNote = new Label("Applies on next launch.");
        openTabNote.getStyleClass().add("info-label-small");
        grid.add(openTabNote, 2, row++);

        // Config file location
        Label configPathLabel = new Label("Config file: " + configService.getConfigPath());
        configPathLabel.getStyleClass().add("info-label-small");

        pane.getChildren().addAll(grid, new Separator(), configPathLabel);

        return pane;
    }

    private static String openTabKeyToLabel(String key) {
        if (key == null) return OPEN_TAB_SEARCH_LABEL;
        switch (key) {
            case "ontheday": return OPEN_TAB_ONTHEDAY_LABEL;
            case "lastused": return OPEN_TAB_LASTUSED_LABEL;
            default: return OPEN_TAB_SEARCH_LABEL;
        }
    }

    private static String openTabLabelToKey(String label) {
        if (OPEN_TAB_ONTHEDAY_LABEL.equals(label)) return "ontheday";
        if (OPEN_TAB_LASTUSED_LABEL.equals(label)) return "lastused";
        return "search";
    }

    private VBox createLoggingPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Enable logging
        grid.add(new Label("Enable Logging:"), 0, row);
        loggingEnabledCheckbox = new CheckBox("Write logs to file");
        grid.add(loggingEnabledCheckbox, 1, row++);

        // Log level
        grid.add(new Label("Log Level:"), 0, row);
        logLevelCombo = new ComboBox<>();
        logLevelCombo.getItems().addAll("DEBUG", "INFO", "WARN", "ERROR");
        logLevelCombo.setPrefWidth(120);
        grid.add(logLevelCombo, 1, row++);

        // Log level descriptions
        Label levelDescLabel = new Label(
                "DEBUG: All messages including detailed debug info\n" +
                "INFO: General information, warnings, and errors\n" +
                "WARN: Warnings and errors only\n" +
                "ERROR: Errors only"
        );
        levelDescLabel.getStyleClass().add("info-label-small");

        // Log file location
        String logPath = System.getProperty("user.home") + "/.photostat/photostat.log";
        Label logPathLabel = new Label("Log file: " + logPath);
        logPathLabel.getStyleClass().add("info-label-small");

        // Log file buttons
        Button openLogButton = new Button("Open Log File");
        openLogButton.setOnAction(e -> openLogFile(logPath));

        Button clearLogButton = new Button("Clear Log File");
        clearLogButton.setOnAction(e -> clearLogFile(logPath));

        HBox logButtonsBox = new HBox(10, openLogButton, clearLogButton);

        // Note about restart
        Label noteLabel = new Label("Note: Changes to logging settings require an application restart to take effect.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-style: italic;");
        noteLabel.getStyleClass().add("text-warning");

        pane.getChildren().addAll(grid, new Separator(), levelDescLabel, logPathLabel, logButtonsBox, new Separator(), noteLabel);

        return pane;
    }

    private VBox createCachePane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Enable cache
        grid.add(new Label("Enable Disk Cache:"), 0, row);
        cacheEnabledCheckbox = new CheckBox("Cache thumbnails to disk");
        grid.add(cacheEnabledCheckbox, 1, row++);

        // Max cache size
        grid.add(new Label("Max Cache Size:"), 0, row);
        cacheMaxSizeSpinner = new Spinner<>(100, 5000, 500, 100);
        cacheMaxSizeSpinner.setEditable(true);
        cacheMaxSizeSpinner.setPrefWidth(100);
        grid.add(cacheMaxSizeSpinner, 1, row);
        grid.add(new Label("MB"), 2, row++);

        // Cache location
        ThumbnailService thumbnailService = ThumbnailService.getInstance();
        String cachePath = thumbnailService.getDiskCacheDir().toString();
        Label cachePathLabel = new Label("Cache location: " + cachePath);
        cachePathLabel.getStyleClass().add("info-label-small");

        // Cache statistics
        cacheStatsLabel = new Label();
        updateCacheStats();
        cacheStatsLabel.getStyleClass().add("info-label-small");

        // Cache buttons
        Button refreshStatsButton = new Button("Refresh Stats");
        refreshStatsButton.setOnAction(e -> updateCacheStats());

        Button clearCacheButton = new Button("Clear Cache");
        clearCacheButton.setOnAction(e -> clearThumbnailCache());

        Button preCacheButton = new Button("Pre-cache Thumbnails");
        preCacheButton.setOnAction(e -> preCacheThumbnails());

        HBox cacheButtonsBox = new HBox(10, refreshStatsButton, clearCacheButton, preCacheButton);

        // Info label
        Label infoLabel = new Label(
                "Disk caching speeds up thumbnail loading by storing generated thumbnails. " +
                "Old thumbnails are automatically removed when the cache exceeds the size limit."
        );
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("info-label");

        pane.getChildren().addAll(grid, new Separator(), cachePathLabel, cacheStatsLabel, cacheButtonsBox, new Separator(), infoLabel);

        return pane;
    }

    private ScrollPane createAIPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        // Provider selection
        GridPane providerGrid = new GridPane();
        providerGrid.setHgap(10);
        providerGrid.setVgap(10);

        providerGrid.add(new Label("AI Provider:"), 0, 0);
        aiProviderCombo = new ComboBox<>();
        aiProviderCombo.getItems().addAll("Claude", "Gemini", "Ollama", "Moondream");
        aiProviderCombo.setPrefWidth(150);
        providerGrid.add(aiProviderCombo, 1, 0);

        Label providerInfoLabel = new Label("Select which AI service to use for image analysis");
        providerInfoLabel.getStyleClass().add("info-label-small");
        providerGrid.add(providerInfoLabel, 2, 0);

        // Claude settings section
        TitledPane claudeSection = new TitledPane();
        claudeSection.setText("Claude Settings");
        claudeSection.setCollapsible(false);

        GridPane claudeGrid = new GridPane();
        claudeGrid.setHgap(10);
        claudeGrid.setVgap(10);
        claudeGrid.setPadding(new Insets(10));

        claudeGrid.add(new Label("API Key:"), 0, 0);
        claudeApiKeyField = new PasswordField();
        claudeApiKeyField.setPromptText("sk-ant-...");
        claudeApiKeyField.setPrefWidth(250);
        claudeGrid.add(claudeApiKeyField, 1, 0);

        claudeGrid.add(new Label("Model:"), 0, 1);
        claudeModelCombo = new ComboBox<>();
        claudeModelCombo.getItems().addAll(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022"
        );
        claudeModelCombo.setPrefWidth(250);
        claudeGrid.add(claudeModelCombo, 1, 1);

        Button testClaudeButton = new Button("Test");
        Label claudeStatusLabel = new Label("");
        testClaudeButton.setOnAction(e -> testClaudeApi(claudeStatusLabel));
        HBox claudeTestBox = new HBox(10, testClaudeButton, claudeStatusLabel);
        claudeGrid.add(claudeTestBox, 1, 2);

        Label claudeInfoLabel = new Label("Get API key: https://console.anthropic.com/");
        claudeInfoLabel.getStyleClass().add("info-label-small");
        claudeGrid.add(claudeInfoLabel, 1, 3);

        claudeSection.setContent(claudeGrid);

        // Gemini settings section
        TitledPane geminiSection = new TitledPane();
        geminiSection.setText("Gemini Settings");
        geminiSection.setCollapsible(false);

        GridPane geminiGrid = new GridPane();
        geminiGrid.setHgap(10);
        geminiGrid.setVgap(10);
        geminiGrid.setPadding(new Insets(10));

        geminiGrid.add(new Label("API Key:"), 0, 0);
        geminiApiKeyField = new PasswordField();
        geminiApiKeyField.setPromptText("AIza...");
        geminiApiKeyField.setPrefWidth(250);
        geminiGrid.add(geminiApiKeyField, 1, 0);

        geminiGrid.add(new Label("Model:"), 0, 1);
        geminiModelCombo = new ComboBox<>();
        geminiModelCombo.getItems().addAll(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro"
        );
        geminiModelCombo.setPrefWidth(250);
        geminiGrid.add(geminiModelCombo, 1, 1);

        Button testGeminiButton = new Button("Test");
        Label geminiStatusLabel = new Label("");
        testGeminiButton.setOnAction(e -> testGeminiApi(geminiStatusLabel));
        HBox geminiTestBox = new HBox(10, testGeminiButton, geminiStatusLabel);
        geminiGrid.add(geminiTestBox, 1, 2);

        Label geminiInfoLabel = new Label("Get API key: https://aistudio.google.com/apikey");
        geminiInfoLabel.getStyleClass().add("info-label-small");
        geminiGrid.add(geminiInfoLabel, 1, 3);

        geminiSection.setContent(geminiGrid);

        // Ollama settings section
        TitledPane ollamaSection = new TitledPane();
        ollamaSection.setText("Ollama Settings (OpenAI-Compatible)");
        ollamaSection.setCollapsible(false);

        GridPane ollamaGrid = new GridPane();
        ollamaGrid.setHgap(10);
        ollamaGrid.setVgap(10);
        ollamaGrid.setPadding(new Insets(10));

        ollamaGrid.add(new Label("Base URL:"), 0, 0);
        ollamaBaseUrlField = new TextField();
        ollamaBaseUrlField.setPromptText("http://localhost:11434/v1");
        ollamaBaseUrlField.setPrefWidth(250);
        ollamaGrid.add(ollamaBaseUrlField, 1, 0);

        ollamaGrid.add(new Label("API Key:"), 0, 1);
        ollamaApiKeyField = new PasswordField();
        ollamaApiKeyField.setPromptText("(optional)");
        ollamaApiKeyField.setPrefWidth(250);
        ollamaGrid.add(ollamaApiKeyField, 1, 1);

        ollamaGrid.add(new Label("Model:"), 0, 2);
        ollamaModelCombo = new ComboBox<>();
        ollamaModelCombo.getItems().addAll("llava", "llama3.2-vision", "minicpm-v");
        ollamaModelCombo.setEditable(true);
        ollamaModelCombo.setPrefWidth(250);
        ollamaGrid.add(ollamaModelCombo, 1, 2);

        Button testOllamaButton = new Button("Test");
        Label ollamaStatusLabel = new Label("");
        testOllamaButton.setOnAction(e -> testOllamaApi(ollamaStatusLabel));
        HBox ollamaTestBox = new HBox(10, testOllamaButton, ollamaStatusLabel);
        ollamaGrid.add(ollamaTestBox, 1, 3);

        Label ollamaInfoLabel = new Label(
                "Use a local Ollama or other OpenAI-compatible endpoint.\n" +
                "Typical local URL: http://localhost:11434/v1\n" +
                "Model must support vision for image analysis."
        );
        ollamaInfoLabel.setWrapText(true);
        ollamaInfoLabel.getStyleClass().add("info-label-small");
        ollamaGrid.add(ollamaInfoLabel, 1, 4);

        ollamaSection.setContent(ollamaGrid);

        // Moondream settings section
        TitledPane moondreamSection = new TitledPane();
        moondreamSection.setText("Moondream Settings (Local AI)");
        moondreamSection.setCollapsible(false);

        GridPane moondreamGrid = new GridPane();
        moondreamGrid.setHgap(10);
        moondreamGrid.setVgap(10);
        moondreamGrid.setPadding(new Insets(10));

        // Backend: run the model locally (spawn Python) or via the Docker service.
        moondreamGrid.add(new Label("Backend:"), 0, 0);
        moondreamModeCombo = new ComboBox<>();
        moondreamModeCombo.getItems().addAll(MODE_LOCAL_LABEL, MODE_DOCKER_LABEL);
        moondreamModeCombo.setPrefWidth(250);
        moondreamGrid.add(moondreamModeCombo, 1, 0);

        moondreamGrid.add(new Label("Docker Endpoint:"), 0, 1);
        moondreamEndpointField = new TextField();
        moondreamEndpointField.setPromptText("http://localhost:8002");
        moondreamEndpointField.setPrefWidth(250);
        moondreamGrid.add(moondreamEndpointField, 1, 1);

        moondreamGrid.add(new Label("Python Path:"), 0, 2);
        moondreamPythonPathField = new TextField();
        moondreamPythonPathField.setPromptText("python3");
        moondreamPythonPathField.setPrefWidth(250);
        moondreamGrid.add(moondreamPythonPathField, 1, 2);

        moondreamGrid.add(new Label("Model:"), 0, 3);
        moondreamModelCombo = new ComboBox<>();
        moondreamModelCombo.getItems().addAll(
            "vikhyatk/moondream2"
        );
        moondreamModelCombo.setEditable(true);
        moondreamModelCombo.setPrefWidth(250);
        moondreamGrid.add(moondreamModelCombo, 1, 3);

        Button testMoondreamButton = new Button("Test");
        moondreamStatusLabel = new Label("");
        testMoondreamButton.setOnAction(e -> testMoondreamSetup());
        HBox moondreamTestBox = new HBox(10, testMoondreamButton, moondreamStatusLabel);
        moondreamGrid.add(moondreamTestBox, 1, 4);

        // Enable only the fields relevant to the selected backend.
        moondreamModeCombo.valueProperty().addListener((obs, o, mode) -> {
            boolean docker = MODE_DOCKER_LABEL.equals(mode);
            moondreamEndpointField.setDisable(!docker);
            moondreamPythonPathField.setDisable(docker);
        });

        Label moondreamInfoLabel = new Label(
            "Free local AI analysis. No API key needed.\n" +
            "Local: pip install \"transformers>=4.51,<5\" torch Pillow accelerate (first run downloads ~1.5 GB).\n" +
            "Docker: run the analysis container (see docker/README.md) and point the endpoint at it.\n" +
            "Slower than cloud APIs on CPU; a GPU (Docker) is much faster."
        );
        moondreamInfoLabel.setWrapText(true);
        moondreamInfoLabel.getStyleClass().add("info-label-small");
        moondreamGrid.add(moondreamInfoLabel, 1, 5);

        moondreamSection.setContent(moondreamGrid);

        // Cost comparison info
        Label costInfoLabel = new Label(
            "Cost Comparison (approximate per 1000 images):\n" +
            "• Claude Sonnet: ~$1.50-3.00 (best quality)\n" +
            "• Claude Haiku: ~$0.15-0.30 (fast, good quality)\n" +
            "• Gemini Flash: ~$0.05-0.10 (cheapest)\n" +
            "• Moondream: Free (local, no API key needed)"
        );
        costInfoLabel.setWrapText(true);
        costInfoLabel.getStyleClass().add("info-label");

        // Analysis Prompt section
        TitledPane promptSection = new TitledPane();
        promptSection.setText("Analysis Prompt");
        promptSection.setCollapsible(false);

        VBox promptContent = new VBox(10);
        promptContent.setPadding(new Insets(10));

        analysisPromptArea = new TextArea(configService.getClaudeAnalysisPrompt());
        analysisPromptArea.setWrapText(true);
        analysisPromptArea.setPrefRowCount(8);

        Button resetPromptButton = new Button("Reset to Default");
        resetPromptButton.setOnAction(e -> analysisPromptArea.setText(ConfigService.getDefaultAnalysisPrompt()));

        promptContent.getChildren().addAll(analysisPromptArea, resetPromptButton);
        promptSection.setContent(promptContent);

        pane.getChildren().addAll(providerGrid, new Separator(), claudeSection, geminiSection, ollamaSection, moondreamSection, new Separator(), costInfoLabel, promptSection);

        // Wrap in ScrollPane for vertical scrolling
        ScrollPane scrollPane = new ScrollPane(pane);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        return scrollPane;
    }

    private ScrollPane createImageGenerationPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        // Luma AI settings
        TitledPane lumaSection = new TitledPane();
        lumaSection.setText("Luma AI Settings");
        lumaSection.setCollapsible(false);

        GridPane lumaGrid = new GridPane();
        lumaGrid.setHgap(10);
        lumaGrid.setVgap(10);
        lumaGrid.setPadding(new Insets(10));

        int row = 0;

        lumaGrid.add(new Label("API Key:"), 0, row);
        lumaApiKeyField = new PasswordField();
        lumaApiKeyField.setPromptText("luma-...");
        lumaApiKeyField.setPrefWidth(250);
        lumaGrid.add(lumaApiKeyField, 1, row++);

        // Test button
        Button testLumaButton = new Button("Test");
        Label lumaStatusLabel = new Label("");
        testLumaButton.setOnAction(e -> testLumaApi(lumaStatusLabel, testLumaButton));
        HBox lumaTestBox = new HBox(10, testLumaButton, lumaStatusLabel);
        lumaGrid.add(lumaTestBox, 1, row++);

        Label lumaInfoLabel = new Label("Get API key: https://lumalabs.ai/dream-machine/api");
        lumaInfoLabel.getStyleClass().add("info-label-small");
        lumaGrid.add(lumaInfoLabel, 1, row++);

        // ImgBB API key (needed to host images for Luma)
        lumaGrid.add(new Separator(), 0, row, 2, 1);
        row++;

        Label imgbbHeader = new Label("Image Hosting (ImgBB)");
        imgbbHeader.setStyle("-fx-font-weight: bold;");
        lumaGrid.add(imgbbHeader, 0, row++, 2, 1);

        lumaGrid.add(new Label("ImgBB API Key:"), 0, row);
        imgbbApiKeyField = new PasswordField();
        imgbbApiKeyField.setPromptText("ImgBB API key");
        imgbbApiKeyField.setPrefWidth(250);
        lumaGrid.add(imgbbApiKeyField, 1, row++);

        Label imgbbInfoLabel = new Label(
            "Required: Luma needs publicly hosted images.\n" +
            "ImgBB provides free image hosting.\n" +
            "Get a free API key: https://api.imgbb.com/"
        );
        imgbbInfoLabel.setWrapText(true);
        imgbbInfoLabel.getStyleClass().add("info-label-small");
        lumaGrid.add(imgbbInfoLabel, 1, row++);

        lumaSection.setContent(lumaGrid);

        // Default settings
        TitledPane defaultsSection = new TitledPane();
        defaultsSection.setText("Default Generation Settings");
        defaultsSection.setCollapsible(false);

        GridPane defaultsGrid = new GridPane();
        defaultsGrid.setHgap(10);
        defaultsGrid.setVgap(10);
        defaultsGrid.setPadding(new Insets(10));

        int dRow = 0;

        // Default output directory
        defaultsGrid.add(new Label("Output Directory:"), 0, dRow);
        lumaOutputDirField = new TextField();
        lumaOutputDirField.setPromptText("(default: home directory)");
        lumaOutputDirField.setPrefWidth(200);
        Button browseDirButton = new Button("Browse...");
        browseDirButton.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Default Output Directory");
            String currentDir = lumaOutputDirField.getText().trim();
            if (!currentDir.isEmpty()) {
                java.io.File dir = new java.io.File(currentDir);
                if (dir.isDirectory()) {
                    chooser.setInitialDirectory(dir);
                }
            }
            java.io.File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (selected != null) {
                lumaOutputDirField.setText(selected.getAbsolutePath());
            }
        });
        HBox dirBox = new HBox(10, lumaOutputDirField, browseDirButton);
        HBox.setHgrow(lumaOutputDirField, Priority.ALWAYS);
        defaultsGrid.add(dirBox, 1, dRow++);

        // Default aspect ratio
        defaultsGrid.add(new Label("Aspect Ratio:"), 0, dRow);
        lumaAspectRatioCombo = new ComboBox<>();
        lumaAspectRatioCombo.getItems().addAll("1:1", "16:9", "9:16", "4:3", "3:4", "21:9", "9:21");
        lumaAspectRatioCombo.setPrefWidth(150);
        defaultsGrid.add(lumaAspectRatioCombo, 1, dRow++);

        // Default reference type
        defaultsGrid.add(new Label("Reference Type:"), 0, dRow);
        lumaRefTypeCombo = new ComboBox<>();
        lumaRefTypeCombo.getItems().addAll("Image Reference", "Style Reference", "Modify Image");
        lumaRefTypeCombo.setPrefWidth(150);
        defaultsGrid.add(lumaRefTypeCombo, 1, dRow++);

        // Default reference weight
        defaultsGrid.add(new Label("Reference Weight:"), 0, dRow);
        lumaRefWeightSlider = new Slider(0.0, 1.0, 0.85);
        lumaRefWeightSlider.setPrefWidth(150);
        lumaRefWeightSlider.setMajorTickUnit(0.25);
        lumaRefWeightSlider.setShowTickLabels(true);
        Label weightLabel = new Label(String.format("%.2f", lumaRefWeightSlider.getValue()));
        lumaRefWeightSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            weightLabel.setText(String.format("%.2f", newVal.doubleValue()))
        );
        HBox weightBox = new HBox(10, lumaRefWeightSlider, weightLabel);
        defaultsGrid.add(weightBox, 1, dRow++);

        defaultsSection.setContent(defaultsGrid);

        // Info
        Label infoLabel = new Label(
            "Luma AI generates new images from text prompts and reference images.\n" +
            "Select images in the search results and click 'Generate Image' to start.\n" +
            "Generation typically takes 10-30 seconds. API usage incurs costs."
        );
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("info-label");

        pane.getChildren().addAll(lumaSection, defaultsSection, new Separator(), infoLabel);

        ScrollPane scrollPane = new ScrollPane(pane);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        return scrollPane;
    }

    private void testLumaApi(Label statusLabel, Button testButton) {
        // Temporarily apply the current field value
        String apiKey = lumaApiKeyField.getText().trim();
        if (apiKey.isEmpty()) {
            statusLabel.setText("Please enter an API key");
            setStatusStyle(statusLabel, "text-error");
            return;
        }

        statusLabel.setText("Testing...");
        setStatusStyle(statusLabel, "text-muted");
        testButton.setDisable(true);

        new Thread(() -> {
            String original = configService.getLumaApiKey();
            configService.setLumaApiKey(apiKey);
            com.photostat.services.LumaService lumaService = com.photostat.services.LumaService.getInstance();
            String error = lumaService.testConnection();
            configService.setLumaApiKey(original);

            Platform.runLater(() -> {
                testButton.setDisable(false);
                if (error == null) {
                    statusLabel.setText("Connection successful!");
                    setStatusStyle(statusLabel, "text-success");
                } else {
                    statusLabel.setText(error.length() > 80 ? error.substring(0, 80) + "..." : error);
                    setStatusStyle(statusLabel, "text-error");
                }
            });
        }).start();
    }

    private VBox createFacesPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Enable face recognition
        grid.add(new Label("Enable:"), 0, row);
        facesEnabledCheckbox = new CheckBox("Enable face detection and recognition");
        grid.add(facesEnabledCheckbox, 1, row++, 2, 1);

        // Backend: local Python or the Docker service.
        grid.add(new Label("Backend:"), 0, row);
        facesModeCombo = new ComboBox<>();
        facesModeCombo.getItems().addAll(MODE_LOCAL_LABEL, MODE_DOCKER_LABEL);
        facesModeCombo.setPrefWidth(200);
        grid.add(facesModeCombo, 1, row++, 2, 1);

        // Docker endpoint
        grid.add(new Label("Docker Endpoint:"), 0, row);
        facesEndpointField = new TextField();
        facesEndpointField.setPromptText("http://localhost:8001");
        facesEndpointField.setPrefWidth(200);
        grid.add(facesEndpointField, 1, row++, 2, 1);

        // Python path
        grid.add(new Label("Python Path:"), 0, row);
        facesPythonPathField = new TextField();
        facesPythonPathField.setPromptText("python3");
        facesPythonPathField.setPrefWidth(200);
        grid.add(facesPythonPathField, 1, row);

        Button checkPythonButton = new Button("Check");
        grid.add(checkPythonButton, 2, row++);

        // Status
        grid.add(new Label("Status:"), 0, row);
        facesPythonStatusLabel = new Label("");
        grid.add(facesPythonStatusLabel, 1, row++, 2, 1);

        checkPythonButton.setOnAction(e -> checkFacesPython());

        // Enable only the fields relevant to the selected backend.
        facesModeCombo.valueProperty().addListener((obs, o, mode) -> {
            boolean docker = MODE_DOCKER_LABEL.equals(mode);
            facesEndpointField.setDisable(!docker);
            facesPythonPathField.setDisable(docker);
        });

        // Detection confidence threshold
        grid.add(new Label("Detection Confidence:"), 0, row);
        facesConfidenceSlider = new Slider(0.3, 0.9, 0.6);
        facesConfidenceSlider.setShowTickLabels(true);
        facesConfidenceSlider.setShowTickMarks(true);
        facesConfidenceSlider.setMajorTickUnit(0.1);
        facesConfidenceSlider.setBlockIncrement(0.05);
        facesConfidenceSlider.setPrefWidth(200);
        Label confValueLabel = new Label(String.format("%.2f", facesConfidenceSlider.getValue()));
        facesConfidenceSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                confValueLabel.setText(String.format("%.2f", newVal.doubleValue())));
        HBox confBox = new HBox(10, facesConfidenceSlider, confValueLabel);
        grid.add(confBox, 1, row++, 2, 1);

        // Cluster threshold
        grid.add(new Label("Cluster Threshold:"), 0, row);
        facesClusterSlider = new Slider(0.3, 0.9, 0.6);
        facesClusterSlider.setShowTickLabels(true);
        facesClusterSlider.setShowTickMarks(true);
        facesClusterSlider.setMajorTickUnit(0.1);
        facesClusterSlider.setBlockIncrement(0.05);
        facesClusterSlider.setPrefWidth(200);
        Label clusterValueLabel = new Label(String.format("%.2f", facesClusterSlider.getValue()));
        facesClusterSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                clusterValueLabel.setText(String.format("%.2f", newVal.doubleValue())));
        HBox clusterBox = new HBox(10, facesClusterSlider, clusterValueLabel);
        grid.add(clusterBox, 1, row++, 2, 1);

        // Info label
        Label infoLabel = new Label(
                "Face recognition requires Python 3 with InsightFace and ONNX Runtime.\n" +
                "Install with: pip install insightface onnxruntime\n" +
                "The InsightFace buffalo_l model (~350MB) auto-downloads on first scan.\n" +
                "For GPU acceleration: pip install onnxruntime-gpu"
        );
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("info-label");

        pane.getChildren().addAll(grid, new Separator(), infoLabel);

        return pane;
    }

    private VBox createCloudUploadPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // rclone path
        grid.add(new Label("rclone Path:"), 0, row);
        rclonePathField = new TextField();
        rclonePathField.setPromptText("rclone");
        rclonePathField.setPrefWidth(200);
        grid.add(rclonePathField, 1, row);

        Button testRcloneButton = new Button("Test");
        Label rcloneVersionLabel = new Label("");
        testRcloneButton.setOnAction(e -> {
            rcloneVersionLabel.setText("Checking...");
            setStatusStyle(rcloneVersionLabel, "text-muted");
            new Thread(() -> {
                // Temporarily set path for the check
                String original = configService.getRclonePath();
                String testPath = rclonePathField.getText().trim();
                if (!testPath.isEmpty()) {
                    configService.setRclonePath(testPath);
                }
                RcloneService rcloneService = RcloneService.getInstance();
                String version = rcloneService.getVersion();
                configService.setRclonePath(original);
                Platform.runLater(() -> {
                    if (version != null) {
                        rcloneVersionLabel.setText(version);
                        setStatusStyle(rcloneVersionLabel, "text-success");
                    } else {
                        rcloneVersionLabel.setText("rclone not found");
                        setStatusStyle(rcloneVersionLabel, "text-error");
                    }
                });
            }).start();
        });
        HBox rcloneTestBox = new HBox(10, testRcloneButton, rcloneVersionLabel);
        grid.add(rcloneTestBox, 2, row++);

        // Remote name
        grid.add(new Label("Remote Name:"), 0, row);
        rcloneRemoteNameField = new TextField();
        rcloneRemoteNameField.setPromptText("gdrive");
        rcloneRemoteNameField.setPrefWidth(200);
        grid.add(rcloneRemoteNameField, 1, row);

        Button listRemotesButton = new Button("List Remotes");
        listRemotesButton.setOnAction(e -> {
            new Thread(() -> {
                String original = configService.getRclonePath();
                String testPath = rclonePathField.getText().trim();
                if (!testPath.isEmpty()) {
                    configService.setRclonePath(testPath);
                }
                RcloneService rcloneService = RcloneService.getInstance();
                List<String> remotes = rcloneService.listRemotes();
                configService.setRclonePath(original);
                Platform.runLater(() -> {
                    if (remotes.isEmpty()) {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("rclone Remotes");
                        alert.setHeaderText(null);
                        alert.setContentText("No remotes configured. Run 'rclone config' to set up a remote.");
                        alert.show();
                    } else {
                        ChoiceDialog<String> dialog = new ChoiceDialog<>(remotes.get(0), remotes);
                        dialog.setTitle("Select Remote");
                        dialog.setHeaderText("Available rclone remotes:");
                        dialog.setContentText("Remote:");
                        dialog.showAndWait().ifPresent(selected -> rcloneRemoteNameField.setText(selected));
                    }
                });
            }).start();
        });
        grid.add(listRemotesButton, 2, row++);

        // Remote path
        grid.add(new Label("Remote Path:"), 0, row);
        rcloneRemotePathField = new TextField();
        rcloneRemotePathField.setPromptText("photos/backup");
        rcloneRemotePathField.setPrefWidth(200);
        grid.add(rcloneRemotePathField, 1, row++);

        // Test connection button
        Button testConnectionButton = new Button("Test Connection");
        Label connectionTestLabel = new Label("");
        testConnectionButton.setOnAction(e -> {
            String remoteName = rcloneRemoteNameField.getText().trim();
            if (remoteName.isEmpty()) {
                connectionTestLabel.setText("Please enter a remote name");
                setStatusStyle(connectionTestLabel, "text-error");
                return;
            }
            connectionTestLabel.setText("Testing...");
            setStatusStyle(connectionTestLabel, "text-muted");
            String remotePath = rcloneRemotePathField.getText().trim();
            new Thread(() -> {
                String original = configService.getRclonePath();
                String testPath = rclonePathField.getText().trim();
                if (!testPath.isEmpty()) {
                    configService.setRclonePath(testPath);
                }
                RcloneService rcloneService = RcloneService.getInstance();
                String error = rcloneService.testConnection(remoteName, remotePath);
                configService.setRclonePath(original);
                Platform.runLater(() -> {
                    if (error == null) {
                        connectionTestLabel.setText("Connection successful!");
                        setStatusStyle(connectionTestLabel, "text-success");
                    } else {
                        connectionTestLabel.setText(error.length() > 80 ? error.substring(0, 80) + "..." : error);
                        setStatusStyle(connectionTestLabel, "text-error");
                    }
                });
            }).start();
        });
        HBox connTestBox = new HBox(10, testConnectionButton, connectionTestLabel);
        connectionTestLabel.setWrapText(true);
        HBox.setHgrow(connectionTestLabel, Priority.ALWAYS);
        grid.add(connTestBox, 0, row++, 3, 1);

        // Upload directories
        Label uploadDirsLabel = new Label("Upload Directories:");
        uploadDirsLabel.setStyle("-fx-font-weight: bold;");

        rcloneUploadDirsListView = new ListView<>();
        rcloneUploadDirsListView.setPrefHeight(120);
        rcloneUploadDirsListView.getItems().addAll(configService.getRcloneUploadDirectories());

        Button addDirButton = new Button("Add...");
        addDirButton.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Upload Directory");
            java.io.File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (selected != null) {
                String path = selected.getAbsolutePath();
                if (!rcloneUploadDirsListView.getItems().contains(path)) {
                    rcloneUploadDirsListView.getItems().add(path);
                }
            }
        });

        Button removeDirButton = new Button("Remove");
        removeDirButton.setOnAction(e -> {
            String selected = rcloneUploadDirsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                rcloneUploadDirsListView.getItems().remove(selected);
            }
        });

        HBox dirButtonsBox = new HBox(10, addDirButton, removeDirButton);

        // Info label
        Label infoLabel = new Label(
                "rclone must be installed separately. Run 'rclone config' in a terminal to set up a remote.\n" +
                "Upload directories are separate from indexing directories.\n" +
                "Download rclone: https://rclone.org/downloads/"
        );
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("info-label");

        pane.getChildren().addAll(grid, new Separator(), uploadDirsLabel, rcloneUploadDirsListView, dirButtonsBox, new Separator(), infoLabel);

        ScrollPane scrollPane = new ScrollPane(pane);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // Wrap in VBox so Tab can host it properly
        VBox wrapper = new VBox(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return wrapper;
    }

    private void checkFacesPython() {
        facesPythonStatusLabel.setText("Checking...");
        setStatusStyle(facesPythonStatusLabel, "text-muted");

        // Temporarily apply the in-dialog values so the check reflects unsaved edits.
        final boolean docker = MODE_DOCKER_LABEL.equals(facesModeCombo.getValue());
        String pythonPath = facesPythonPathField.getText().trim();
        if (pythonPath.isEmpty()) pythonPath = "python3";
        String endpoint = facesEndpointField.getText().trim();
        if (endpoint.isEmpty()) endpoint = "http://localhost:8001";

        final String finalPythonPath = pythonPath;
        final String finalEndpoint = endpoint;

        new Thread(() -> {
            // Save current, set temp, check, restore
            String origMode = configService.getFacesMode();
            String origPython = configService.getFacesPythonPath();
            String origEndpoint = configService.getFacesEndpoint();
            try {
                configService.setFacesMode(docker ? "docker" : "local");
                configService.setFacesPythonPath(finalPythonPath);
                configService.setFacesEndpoint(finalEndpoint);

                com.photostat.services.FaceRecognitionService faceService =
                        com.photostat.services.FaceRecognitionService.getInstance();
                boolean available = faceService.isPythonAvailable();
                String versionInfo = faceService.getPythonVersionInfo();

                Platform.runLater(() -> {
                    if (available) {
                        facesPythonStatusLabel.setText("Available - " + versionInfo);
                        setStatusStyle(facesPythonStatusLabel, "text-success");
                    } else {
                        facesPythonStatusLabel.setText(docker
                                ? "Service not reachable at " + finalEndpoint
                                : "Not found or missing dependencies");
                        setStatusStyle(facesPythonStatusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    facesPythonStatusLabel.setText("Error: " + e.getMessage());
                    setStatusStyle(facesPythonStatusLabel, "text-error");
                });
            } finally {
                configService.setFacesMode(origMode);
                configService.setFacesPythonPath(origPython);
                configService.setFacesEndpoint(origEndpoint);
            }
        }).start();
    }

    private void testMoondreamSetup() {
        moondreamStatusLabel.setText("Checking...");
        setStatusStyle(moondreamStatusLabel, "text-muted");

        final boolean docker = MODE_DOCKER_LABEL.equals(moondreamModeCombo.getValue());
        String pythonPath = moondreamPythonPathField.getText().trim();
        if (pythonPath.isEmpty()) pythonPath = "python3";
        String endpoint = moondreamEndpointField.getText().trim();
        if (endpoint.isEmpty()) endpoint = "http://localhost:8002";

        final String finalPythonPath = pythonPath;
        final String finalEndpoint = endpoint;

        new Thread(() -> {
            // Temporarily apply the in-dialog values for the check, then restore.
            String origMode = configService.getMoondreamMode();
            String origPython = configService.getMoondreamPythonPath();
            String origEndpoint = configService.getMoondreamEndpoint();
            try {
                configService.setMoondreamMode(docker ? "docker" : "local");
                configService.setMoondreamPythonPath(finalPythonPath);
                configService.setMoondreamEndpoint(finalEndpoint);

                ImageAnalysisService analysisService = ImageAnalysisService.getInstance();
                boolean available = analysisService.isMoondreamAvailable();
                String versionInfo = analysisService.getMoondreamVersionInfo();

                Platform.runLater(() -> {
                    if (available) {
                        moondreamStatusLabel.setText("Available - " + versionInfo);
                        setStatusStyle(moondreamStatusLabel, "text-success");
                    } else {
                        moondreamStatusLabel.setText(docker
                                ? "Service not reachable at " + finalEndpoint
                                : "Not found. Install: pip install \"transformers>=4.51,<5\" torch Pillow accelerate");
                        setStatusStyle(moondreamStatusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    moondreamStatusLabel.setText("Error: " + e.getMessage());
                    setStatusStyle(moondreamStatusLabel, "text-error");
                });
            } finally {
                configService.setMoondreamMode(origMode);
                configService.setMoondreamPythonPath(origPython);
                configService.setMoondreamEndpoint(origEndpoint);
            }
        }).start();
    }

    private void setStatusStyle(Label label, String styleClass) {
        label.getStyleClass().removeAll("text-muted", "text-error", "text-success");
        label.getStyleClass().add(styleClass);
    }

    private void testGeminiApi(Label statusLabel) {
        statusLabel.setText("Testing...");
        setStatusStyle(statusLabel, "text-muted");

        String apiKey = geminiApiKeyField.getText().trim();
        if (apiKey.isEmpty()) {
            statusLabel.setText("Please enter an API key");
            setStatusStyle(statusLabel, "text-error");
            return;
        }

        new Thread(() -> {
            try {
                com.google.genai.Client client = com.google.genai.Client.builder()
                        .apiKey(apiKey)
                        .build();

                // Simple test - list models or generate minimal content
                com.google.genai.types.Content content = com.google.genai.types.Content.fromParts(
                        com.google.genai.types.Part.fromText("Hi")
                );
                com.google.genai.types.GenerateContentResponse response =
                        client.models.generateContent("gemini-2.0-flash", content, null);

                Platform.runLater(() -> {
                    if (response != null && response.text() != null) {
                        statusLabel.setText("API key is valid!");
                        setStatusStyle(statusLabel, "text-success");
                    } else {
                        statusLabel.setText("Invalid response");
                        setStatusStyle(statusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("API_KEY_INVALID")) {
                        statusLabel.setText("Invalid API key");
                    } else {
                        statusLabel.setText("Error: " + (msg != null ? msg.substring(0, Math.min(30, msg.length())) : "Unknown"));
                    }
                    setStatusStyle(statusLabel, "text-error");
                });
            }
        }).start();
    }

    private void testClaudeApi(Label statusLabel) {
        statusLabel.setText("Testing...");
        setStatusStyle(statusLabel, "text-muted");

        String apiKey = claudeApiKeyField.getText().trim();
        if (apiKey.isEmpty()) {
            statusLabel.setText("Please enter an API key");
            setStatusStyle(statusLabel, "text-error");
            return;
        }

        new Thread(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"claude-sonnet-4-20250514\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"
                    ))
                    .build();

                java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

                Platform.runLater(() -> {
                    if (response.statusCode() == 200) {
                        statusLabel.setText("API key is valid!");
                        setStatusStyle(statusLabel, "text-success");
                    } else if (response.statusCode() == 401) {
                        statusLabel.setText("Invalid API key");
                        setStatusStyle(statusLabel, "text-error");
                    } else {
                        statusLabel.setText("Error: " + response.statusCode());
                        setStatusStyle(statusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    setStatusStyle(statusLabel, "text-error");
                });
            }
        }).start();
    }

    private void testOllamaApi(Label statusLabel) {
        statusLabel.setText("Testing...");
        setStatusStyle(statusLabel, "text-muted");

        String baseUrl = ollamaBaseUrlField.getText().trim();
        String model = ollamaModelCombo.getValue() != null ? ollamaModelCombo.getValue().trim() : "";
        String apiKey = ollamaApiKeyField.getText().trim();

        if (baseUrl.isEmpty() || model.isEmpty()) {
            statusLabel.setText("Enter base URL and model");
            setStatusStyle(statusLabel, "text-error");
            return;
        }

        new Thread(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
                java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"" + model.replace("\"", "\\\"") + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word ok.\"}],\"temperature\":0}"
                        ));
                if (!apiKey.isEmpty()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }

                java.net.http.HttpResponse<String> response = client.send(builder.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

                Platform.runLater(() -> {
                    if (response.statusCode() < 400) {
                        statusLabel.setText("Connection works");
                        setStatusStyle(statusLabel, "text-success");
                    } else {
                        statusLabel.setText("Error: " + response.statusCode());
                        setStatusStyle(statusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    setStatusStyle(statusLabel, "text-error");
                });
            }
        }).start();
    }

    private void updateCacheStats() {
        ThumbnailService thumbnailService = ThumbnailService.getInstance();
        int fileCount = thumbnailService.getDiskCacheFileCount();
        long sizeBytes = thumbnailService.getDiskCacheSize();
        double sizeMB = sizeBytes / (1024.0 * 1024.0);
        cacheStatsLabel.setText(String.format("Cache: %d files, %.1f MB", fileCount, sizeMB));
    }

    private void clearThumbnailCache() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear Thumbnail Cache");
        confirm.setHeaderText(null);
        confirm.setContentText("Are you sure you want to clear the thumbnail cache? This cannot be undone.");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                ThumbnailService.getInstance().clearAllCaches();
                updateCacheStats();

                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Cache Cleared");
                success.setHeaderText(null);
                success.setContentText("Thumbnail cache cleared successfully.");
                success.show();
            }
        });
    }

    private void loadSettings() {
        hostField.setText(configService.getOpenSearchHost());
        portField.setText(String.valueOf(configService.getOpenSearchPort()));
        sslCheckbox.setSelected(configService.isOpenSearchSsl());
        usernameField.setText(configService.getOpenSearchUsername());
        passwordField.setText(configService.getOpenSearchPassword());
        indexNameField.setText(configService.getIndexName());
        exifToolPathField.setText(configService.getExifToolPath());
        batchSizeSpinner.getValueFactory().setValue(configService.getBatchSize());
        String format = configService.getSidecarFormat();
        if ("xmp".equalsIgnoreCase(format)) {
            sidecarFormatCombo.setValue("XMP");
        } else if ("both".equalsIgnoreCase(format)) {
            sidecarFormatCombo.setValue("Both");
        } else {
            sidecarFormatCombo.setValue("JSON");
        }
        sidecarReadBothCheckbox.setSelected(configService.isSidecarReadBoth());
        themeCombo.setValue("dark".equalsIgnoreCase(configService.getTheme()) ? "Dark" : "Light");
        thumbnailSizeSpinner.getValueFactory().setValue(configService.getThumbnailSize());
        resultsPerPageSpinner.getValueFactory().setValue(configService.getResultsPerPage());
        openTabCombo.setValue(openTabKeyToLabel(configService.getOpenTab()));

        // Logging settings
        loggingEnabledCheckbox.setSelected(configService.isLoggingEnabled());
        logLevelCombo.setValue(configService.getLoggingLevel());

        // Cache settings
        cacheEnabledCheckbox.setSelected(configService.isThumbnailCacheEnabled());
        cacheMaxSizeSpinner.getValueFactory().setValue(configService.getThumbnailCacheMaxSizeMB());

        // AI Provider settings
        String provider = configService.getAiProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            aiProviderCombo.setValue("Gemini");
        } else if ("ollama".equalsIgnoreCase(provider)) {
            aiProviderCombo.setValue("Ollama");
        } else if ("moondream".equalsIgnoreCase(provider)) {
            aiProviderCombo.setValue("Moondream");
        } else {
            aiProviderCombo.setValue("Claude");
        }

        // Claude API settings
        claudeApiKeyField.setText(configService.getClaudeApiKey());
        claudeModelCombo.setValue(configService.getClaudeModel());

        // Gemini API settings
        geminiApiKeyField.setText(configService.getGeminiApiKey());
        geminiModelCombo.setValue(configService.getGeminiModel());

        // Ollama settings
        ollamaBaseUrlField.setText(configService.getOllamaBaseUrl());
        ollamaApiKeyField.setText(configService.getOllamaApiKey());
        ollamaModelCombo.setValue(configService.getOllamaModel());

        // Moondream settings
        moondreamPythonPathField.setText(configService.getMoondreamPythonPath());
        moondreamModelCombo.setValue(configService.getMoondreamModel());
        moondreamModeCombo.setValue("docker".equalsIgnoreCase(configService.getMoondreamMode())
                ? MODE_DOCKER_LABEL : MODE_LOCAL_LABEL);
        moondreamEndpointField.setText(configService.getMoondreamEndpoint());

        // Luma AI settings
        lumaApiKeyField.setText(configService.getLumaApiKey());
        imgbbApiKeyField.setText(configService.getImgbbApiKey());
        lumaOutputDirField.setText(configService.getLumaDefaultOutputDirectory());
        lumaAspectRatioCombo.setValue(configService.getLumaDefaultAspectRatio());
        String lumaRefType = configService.getLumaDefaultRefType();
        switch (lumaRefType) {
            case "style_ref": lumaRefTypeCombo.setValue("Style Reference"); break;
            case "modify_image_ref": lumaRefTypeCombo.setValue("Modify Image"); break;
            default: lumaRefTypeCombo.setValue("Image Reference"); break;
        }
        lumaRefWeightSlider.setValue(configService.getLumaDefaultRefWeight());

        // Face recognition settings
        facesEnabledCheckbox.setSelected(configService.isFacesEnabled());
        facesPythonPathField.setText(configService.getFacesPythonPath());
        facesModeCombo.setValue("docker".equalsIgnoreCase(configService.getFacesMode())
                ? MODE_DOCKER_LABEL : MODE_LOCAL_LABEL);
        facesEndpointField.setText(configService.getFacesEndpoint());
        facesConfidenceSlider.setValue(configService.getFacesConfidenceThreshold());
        facesClusterSlider.setValue(configService.getFacesClusterThreshold());

        // rclone settings
        rclonePathField.setText(configService.getRclonePath());
        rcloneRemoteNameField.setText(configService.getRcloneRemoteName());
        rcloneRemotePathField.setText(configService.getRcloneRemotePath());
        rcloneUploadDirsListView.getItems().clear();
        rcloneUploadDirsListView.getItems().addAll(configService.getRcloneUploadDirectories());
    }

    private void saveSettings() {
        configService.setOpenSearchHost(hostField.getText().trim());

        try {
            configService.setOpenSearchPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            configService.setOpenSearchPort(9200);
        }

        configService.setOpenSearchSsl(sslCheckbox.isSelected());
        configService.setOpenSearchUsername(usernameField.getText().trim());
        configService.setOpenSearchPassword(passwordField.getText());
        configService.setIndexName(indexNameField.getText().trim());
        configService.setExifToolPath(exifToolPathField.getText().trim());
        configService.setBatchSize(batchSizeSpinner.getValue());
        String formatChoice = sidecarFormatCombo.getValue();
        if ("XMP".equals(formatChoice)) {
            configService.setSidecarFormat("xmp");
        } else if ("Both".equals(formatChoice)) {
            configService.setSidecarFormat("both");
        } else {
            configService.setSidecarFormat("json");
        }
        configService.setSidecarReadBoth(sidecarReadBothCheckbox.isSelected());
        // Theme
        String selectedTheme = "Dark".equals(themeCombo.getValue()) ? "dark" : "light";
        configService.setTheme(selectedTheme);

        // Apply theme instantly
        javafx.scene.Scene scene = App.getMainScene();
        if (scene != null) {
            App.applyTheme(scene, selectedTheme);
        }

        configService.setThumbnailSize(thumbnailSizeSpinner.getValue());
        configService.setResultsPerPage(resultsPerPageSpinner.getValue());
        configService.setOpenTab(openTabLabelToKey(openTabCombo.getValue()));

        // Logging settings
        configService.setLoggingEnabled(loggingEnabledCheckbox.isSelected());
        configService.setLoggingLevel(logLevelCombo.getValue());

        // Cache settings
        configService.setThumbnailCacheEnabled(cacheEnabledCheckbox.isSelected());
        configService.setThumbnailCacheMaxSizeMB(cacheMaxSizeSpinner.getValue());

        // File extensions
        List<String> selectedExtensions = new ArrayList<>();
        for (Map.Entry<String, CheckBox> entry : extensionCheckboxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selectedExtensions.add(entry.getKey());
            }
        }
        configService.setFileExtensions(selectedExtensions);

        // AI Provider settings
        String selectedProvider = aiProviderCombo.getValue();
        if ("Gemini".equals(selectedProvider)) {
            configService.setAiProvider("gemini");
        } else if ("Ollama".equals(selectedProvider)) {
            configService.setAiProvider("ollama");
        } else if ("Moondream".equals(selectedProvider)) {
            configService.setAiProvider("moondream");
        } else {
            configService.setAiProvider("claude");
        }

        // Claude API settings
        configService.setClaudeApiKey(claudeApiKeyField.getText().trim());
        if (claudeModelCombo.getValue() != null) {
            configService.setClaudeModel(claudeModelCombo.getValue());
        }

        // Gemini API settings
        configService.setGeminiApiKey(geminiApiKeyField.getText().trim());
        if (geminiModelCombo.getValue() != null) {
            configService.setGeminiModel(geminiModelCombo.getValue());
        }

        // Ollama settings
        configService.setOllamaBaseUrl(ollamaBaseUrlField.getText().trim());
        configService.setOllamaApiKey(ollamaApiKeyField.getText().trim());
        if (ollamaModelCombo.getValue() != null && !ollamaModelCombo.getValue().trim().isEmpty()) {
            configService.setOllamaModel(ollamaModelCombo.getValue().trim());
        }

        // Moondream settings
        String moondreamPythonPath = moondreamPythonPathField.getText().trim();
        if (!moondreamPythonPath.isEmpty()) {
            configService.setMoondreamPythonPath(moondreamPythonPath);
        }
        if (moondreamModelCombo.getValue() != null && !moondreamModelCombo.getValue().trim().isEmpty()) {
            configService.setMoondreamModel(moondreamModelCombo.getValue().trim());
        }
        configService.setMoondreamMode(
                MODE_DOCKER_LABEL.equals(moondreamModeCombo.getValue()) ? "docker" : "local");
        String moondreamEndpoint = moondreamEndpointField.getText().trim();
        if (!moondreamEndpoint.isEmpty()) {
            configService.setMoondreamEndpoint(moondreamEndpoint);
        }

        // Analysis prompt
        configService.setClaudeAnalysisPrompt(analysisPromptArea.getText());

        // rclone settings
        String rclonePath = rclonePathField.getText().trim();
        if (!rclonePath.isEmpty()) {
            configService.setRclonePath(rclonePath);
        }
        configService.setRcloneRemoteName(rcloneRemoteNameField.getText().trim());
        configService.setRcloneRemotePath(rcloneRemotePathField.getText().trim());
        configService.setRcloneUploadDirectories(new ArrayList<>(rcloneUploadDirsListView.getItems()));

        // Luma AI settings
        configService.setLumaApiKey(lumaApiKeyField.getText().trim());
        configService.setImgbbApiKey(imgbbApiKeyField.getText().trim());
        String lumaOutputDir = lumaOutputDirField.getText().trim();
        configService.setLumaDefaultOutputDirectory(lumaOutputDir);
        if (lumaAspectRatioCombo.getValue() != null) {
            configService.setLumaDefaultAspectRatio(lumaAspectRatioCombo.getValue());
        }
        String selectedRefType = lumaRefTypeCombo.getValue();
        if (selectedRefType != null) {
            switch (selectedRefType) {
                case "Style Reference": configService.setLumaDefaultRefType("style_ref"); break;
                case "Modify Image": configService.setLumaDefaultRefType("modify_image_ref"); break;
                default: configService.setLumaDefaultRefType("image_ref"); break;
            }
        }
        configService.setLumaDefaultRefWeight(lumaRefWeightSlider.getValue());

        // Face recognition settings
        configService.setFacesEnabled(facesEnabledCheckbox.isSelected());
        String pythonPath = facesPythonPathField.getText().trim();
        if (!pythonPath.isEmpty()) {
            configService.setFacesPythonPath(pythonPath);
        }
        configService.setFacesMode(
                MODE_DOCKER_LABEL.equals(facesModeCombo.getValue()) ? "docker" : "local");
        String facesEndpoint = facesEndpointField.getText().trim();
        if (!facesEndpoint.isEmpty()) {
            configService.setFacesEndpoint(facesEndpoint);
        }
        configService.setFacesConfidenceThreshold(facesConfidenceSlider.getValue());
        configService.setFacesClusterThreshold(facesClusterSlider.getValue());

        configService.saveConfig();
    }

    private void testConnection() {
        connectionStatusLabel.setText("Testing...");
        setStatusStyle(connectionStatusLabel, "text-muted");

        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = 9200;
        }
        boolean ssl = sslCheckbox.isSelected();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        final int finalPort = port;

        Thread thread = new Thread(() -> {
            try {
                openSearchService.connect(host, finalPort, ssl, username, password);
                boolean success = openSearchService.testConnection();

                Platform.runLater(() -> {
                    if (success) {
                        connectionStatusLabel.setText("Connection successful!");
                        setStatusStyle(connectionStatusLabel, "text-success");
                    } else {
                        connectionStatusLabel.setText("Connection failed");
                        setStatusStyle(connectionStatusLabel, "text-error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("Error: " + e.getMessage());
                    setStatusStyle(connectionStatusLabel, "text-error");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void browseForExifTool() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Select ExifTool Executable");

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("Executables", "*.exe")
            );
        }

        java.io.File selected = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (selected != null) {
            exifToolPathField.setText(selected.getAbsolutePath());
        }
    }

    private void openLogFile(String logPath) {
        try {
            java.io.File logFile = new java.io.File(logPath);
            if (!logFile.exists()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Log File");
                alert.setHeaderText(null);
                alert.setContentText("Log file does not exist yet. Enable logging and restart the application.");
                alert.show();
                return;
            }

            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(logFile);
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("notepad", logPath);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", logPath);
                } else {
                    pb = new ProcessBuilder("xdg-open", logPath);
                }
                pb.start();
            }
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Failed to open log file: " + e.getMessage());
            alert.show();
        }
    }

    private void clearLogFile(String logPath) {
        try {
            java.io.File logFile = new java.io.File(logPath);
            if (!logFile.exists()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Log File");
                alert.setHeaderText(null);
                alert.setContentText("Log file does not exist.");
                alert.show();
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Clear Log File");
            confirm.setHeaderText(null);
            confirm.setContentText("Are you sure you want to clear the log file? This cannot be undone.");
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    try {
                        new java.io.FileWriter(logFile, false).close();
                        Alert success = new Alert(Alert.AlertType.INFORMATION);
                        success.setTitle("Log File");
                        success.setHeaderText(null);
                        success.setContentText("Log file cleared successfully.");
                        success.show();
                    } catch (java.io.IOException e) {
                        Alert error = new Alert(Alert.AlertType.ERROR);
                        error.setTitle("Error");
                        error.setHeaderText(null);
                        error.setContentText("Failed to clear log file: " + e.getMessage());
                        error.show();
                    }
                }
            });
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Error: " + e.getMessage());
            alert.show();
        }
    }

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tiff", ".tif",
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf", ".pef"
    );

    private static final int PARALLEL_THREADS = Runtime.getRuntime().availableProcessors() * 2;

    private void preCacheThumbnails() {
        // Check if OpenSearch is connected
        if (!openSearchService.isConnected()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Not Connected");
            alert.setHeaderText(null);
            alert.setContentText("Please connect to OpenSearch first and index some images.");
            alert.show();
            return;
        }

        // Get document count
        long totalDocuments;
        try {
            totalDocuments = openSearchService.getDocumentCount();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Failed to get document count: " + e.getMessage());
            alert.show();
            return;
        }

        if (totalDocuments == 0) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("No Images");
            alert.setHeaderText(null);
            alert.setContentText("No images are indexed. Please index some images first.");
            alert.show();
            return;
        }

        // Create progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle("Pre-caching Thumbnails");
        progressDialog.setHeaderText("Generating thumbnail cache (" + PARALLEL_THREADS + " threads)...");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);

        Label statusLabel = new Label("Initializing...");
        statusLabel.setPrefWidth(400);

        Label statsLabel = new Label("Cached: 0  |  Skipped: 0  |  Failed: 0");

        Label speedLabel = new Label("");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.getChildren().addAll(progressBar, statusLabel, statsLabel, speedLabel);

        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        final long totalDocs = totalDocuments;

        // Create background task
        Task<Void> cacheTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ThumbnailService thumbnailService = ThumbnailService.getInstance();
                long maxCacheSizeBytes = configService.getThumbnailCacheMaxSizeMB() * 1024L * 1024L;

                // Create thread pool for parallel thumbnail generation
                ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);

                // Atomic counters for thread-safe tracking
                AtomicInteger processed = new AtomicInteger(0);
                AtomicInteger cached = new AtomicInteger(0);
                AtomicInteger skipped = new AtomicInteger(0);
                AtomicInteger failed = new AtomicInteger(0);
                AtomicBoolean cacheFull = new AtomicBoolean(false);
                AtomicLong lastUIUpdate = new AtomicLong(0);

                long startTime = System.currentTimeMillis();

                try {
                    // Fetch all file paths using search_after pagination (no 10k limit)
                    Platform.runLater(() -> statusLabel.setText("Fetching file paths from OpenSearch..."));
                    List<String> allFilePaths = openSearchService.searchAllFilePaths(1000);
                    final long totalFiles = allFilePaths.size();

                    // Process all file paths in parallel using CompletionService to avoid
                    // holding all futures in memory simultaneously for large collections.
                    ExecutorCompletionService<Void> completionService =
                            new ExecutorCompletionService<>(executor);
                    int submitted = 0;

                    for (String filePath : allFilePaths) {
                        if (cancelled.get() || cacheFull.get()) break;

                        completionService.submit(() -> {
                            if (cancelled.get() || cacheFull.get()) return null;

                            int currentProcessed = processed.incrementAndGet();
                            String fileName = Path.of(filePath).getFileName().toString();

                            // Check if file exists
                            if (!Files.exists(Path.of(filePath))) {
                                skipped.incrementAndGet();
                                return null;
                            }

                            // Check if extension is supported
                            String ext = getFileExtension(filePath).toLowerCase();
                            if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                                skipped.incrementAndGet();
                                return null;
                            }

                            // Check if already cached
                            if (isThumbnailCached(filePath, thumbnailService)) {
                                skipped.incrementAndGet();
                                throttledUIUpdate(lastUIUpdate, () -> {
                                    double progress = (double) currentProcessed / totalFiles;
                                    progressBar.setProgress(progress);
                                    statusLabel.setText("[" + currentProcessed + "/" + totalFiles + "] Skipped: " +
                                            truncateFilename(fileName));
                                    statsLabel.setText(String.format("Cached: %d  |  Skipped: %d  |  Failed: %d",
                                            cached.get(), skipped.get(), failed.get()));
                                });
                                return null;
                            }

                            // Generate thumbnail
                            try {
                                throttledUIUpdate(lastUIUpdate, () -> {
                                    double progress = (double) currentProcessed / totalFiles;
                                    progressBar.setProgress(progress);
                                    statusLabel.setText("[" + currentProcessed + "/" + totalFiles + "] Caching: " +
                                            truncateFilename(fileName));
                                });

                                thumbnailService.getThumbnail(filePath);
                                int newCached = cached.incrementAndGet();

                                // Update stats periodically
                                throttledUIUpdate(lastUIUpdate, () -> {
                                    statsLabel.setText(String.format("Cached: %d  |  Skipped: %d  |  Failed: %d",
                                            cached.get(), skipped.get(), failed.get()));
                                    // Update speed
                                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                                    if (elapsed > 0 && cached.get() > 0) {
                                        speedLabel.setText(String.format("Speed: %.1f thumbnails/sec", cached.get() / elapsed));
                                    }
                                });

                                // Periodically check cache size
                                if (newCached % 20 == 0) {
                                    long cacheSize = thumbnailService.getDiskCacheSize();
                                    if (cacheSize >= maxCacheSizeBytes * 0.95) {
                                        cacheFull.set(true);
                                    }
                                }

                            } catch (Exception e) {
                                failed.incrementAndGet();
                            }
                            return null;
                        });
                        submitted++;
                    }

                    // Consume completions as they arrive — O(1) memory per task
                    for (int i = 0; i < submitted; i++) {
                        try {
                            completionService.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    // Shutdown executor
                    executor.shutdown();
                    try {
                        executor.awaitTermination(1, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                    }
                }

                // Final update
                double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        statusLabel.setText("Cancelled by user");
                    } else if (cacheFull.get()) {
                        statusLabel.setText("Complete - Cache size limit reached!");
                    } else {
                        statusLabel.setText("Complete!");
                    }
                    statsLabel.setText(String.format("Cached: %d  |  Skipped: %d  |  Failed: %d",
                            cached.get(), skipped.get(), failed.get()));
                    progressBar.setProgress(1.0);
                    if (cached.get() > 0 && elapsedSeconds > 0) {
                        speedLabel.setText(String.format("Completed in %.1f seconds (%.1f thumbnails/sec)",
                                elapsedSeconds, cached.get() / elapsedSeconds));
                    }
                });

                return null;
            }
        };

        // Handle cancel button
        progressDialog.setOnCloseRequest(event -> {
            cancelled.set(true);
        });

        // Handle task completion
        cacheTask.setOnSucceeded(event -> {
            updateCacheStats();
            // Change cancel button to close
            progressDialog.getDialogPane().getButtonTypes().clear();
            progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        });

        cacheTask.setOnFailed(event -> {
            Throwable e = cacheTask.getException();
            Platform.runLater(() -> {
                statusLabel.setText("Error: " + (e != null ? e.getMessage() : "Unknown error"));
            });
            progressDialog.getDialogPane().getButtonTypes().clear();
            progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        });

        // Start the task
        Thread thread = new Thread(cacheTask);
        thread.setDaemon(true);
        thread.start();

        // Show dialog
        progressDialog.showAndWait();

        // Cancel task if dialog is closed
        cancelled.set(true);
    }

    private void throttledUIUpdate(AtomicLong lastUpdate, Runnable uiUpdate) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate.get() >= 100) { // Update at most every 100ms
            lastUpdate.set(now);
            Platform.runLater(uiUpdate);
        }
    }

    private boolean isThumbnailCached(String filePath, ThumbnailService thumbnailService) {
        try {
            Path path = Path.of(filePath);
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            long modTime = attrs.lastModifiedTime().toMillis();
            int thumbSize = configService.getThumbnailSize();

            String input = filePath + "|" + modTime + "|" + thumbSize;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String cacheKey = sb.toString();

            Path cachePath = thumbnailService.getDiskCacheDir().resolve(cacheKey + ".jpg");
            return Files.exists(cachePath);
        } catch (Exception e) {
            return false;
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    private String truncateFilename(String filename) {
        if (filename.length() > 40) {
            return filename.substring(0, 37) + "...";
        }
        return filename;
    }
}
