package com.photostat.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing application configuration.
 */
public class ConfigService {

    private static ConfigService instance;
    private final ObjectMapper objectMapper;
    private final Path configPath;
    private Map<String, Object> config;

    // Default configuration values
    private static final String DEFAULT_OPENSEARCH_HOST = "localhost";
    private static final int DEFAULT_OPENSEARCH_PORT = 9200;
    private static final boolean DEFAULT_OPENSEARCH_SSL = false;
    private static final String DEFAULT_INDEX_NAME = "photostat";
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_THUMBNAIL_SIZE = 200;

    private ConfigService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Determine config path
        String userHome = System.getProperty("user.home");
        Path configDir = Paths.get(userHome, ".photostat");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            System.err.println("Failed to create config directory: " + e.getMessage());
        }
        this.configPath = configDir.resolve("config.json");

        loadConfig();
    }

    public static synchronized ConfigService getInstance() {
        if (instance == null) {
            instance = new ConfigService();
        }
        return instance;
    }

    private void loadConfig() {
        File configFile = configPath.toFile();
        if (configFile.exists()) {
            try {
                config = objectMapper.readValue(configFile, Map.class);
                migrateConfig();
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
                config = createDefaultConfig();
            }
        } else {
            config = createDefaultConfig();
            saveConfig();
        }
    }

    /**
     * Migrate existing config by adding missing keys with default values.
     */
    @SuppressWarnings("unchecked")
    private void migrateConfig() {
        boolean changed = false;

        // Ensure claude section exists
        if (!config.containsKey("claude")) {
            Map<String, Object> claude = new HashMap<>();
            claude.put("api_key", "");
            claude.put("model", "claude-sonnet-4-20250514");
            claude.put("analysis_prompt", getDefaultAnalysisPrompt());
            config.put("claude", claude);
            changed = true;
        } else {
            Map<String, Object> claude = (Map<String, Object>) config.get("claude");

            // Add analysis_prompt if missing
            if (!claude.containsKey("analysis_prompt")) {
                claude.put("analysis_prompt", getDefaultAnalysisPrompt());
                changed = true;
            }

            // Add model if missing
            if (!claude.containsKey("model")) {
                claude.put("model", "claude-sonnet-4-20250514");
                changed = true;
            }
        }

        // Ensure cache section exists
        if (!config.containsKey("cache")) {
            Map<String, Object> cache = new HashMap<>();
            cache.put("enabled", true);
            cache.put("max_size_mb", 500);
            config.put("cache", cache);
            changed = true;
        }

        // Ensure ai section exists (provider selection)
        if (!config.containsKey("ai")) {
            Map<String, Object> ai = new HashMap<>();
            ai.put("provider", "claude");
            config.put("ai", ai);
            changed = true;
        }

        // Ensure gemini section exists
        if (!config.containsKey("gemini")) {
            Map<String, Object> gemini = new HashMap<>();
            gemini.put("api_key", "");
            gemini.put("model", "gemini-2.0-flash");
            config.put("gemini", gemini);
            changed = true;
        }

        // Ensure moondream section exists
        if (!config.containsKey("moondream")) {
            Map<String, Object> moondream = new HashMap<>();
            moondream.put("python_path", DEFAULT_PYTHON_PATH);
            moondream.put("model", "vikhyatk/moondream2");
            config.put("moondream", moondream);
            changed = true;
        }

        // Ensure faces section exists
        if (!config.containsKey("faces")) {
            Map<String, Object> faces = new HashMap<>();
            faces.put("python_path", "python3");
            faces.put("enabled", true);
            faces.put("confidence_threshold", 0.6);
            faces.put("cluster_threshold", 0.6);
            config.put("faces", faces);
            changed = true;
        }

        // Ensure rclone section exists
        if (!config.containsKey("rclone")) {
            Map<String, Object> rclone = new HashMap<>();
            rclone.put("rclone_path", "rclone");
            rclone.put("remote_name", "");
            rclone.put("remote_path", "");
            rclone.put("upload_directories", new ArrayList<>());
            config.put("rclone", rclone);
            changed = true;
        }

        if (changed) {
            saveConfig();
            System.out.println("Config migrated with new default values");
        }
    }

    private Map<String, Object> createDefaultConfig() {
        Map<String, Object> defaultConfig = new HashMap<>();

        // OpenSearch settings
        Map<String, Object> opensearch = new HashMap<>();
        opensearch.put("host", DEFAULT_OPENSEARCH_HOST);
        opensearch.put("port", DEFAULT_OPENSEARCH_PORT);
        opensearch.put("ssl", DEFAULT_OPENSEARCH_SSL);
        opensearch.put("username", "");
        opensearch.put("password", "");
        opensearch.put("index_name", DEFAULT_INDEX_NAME);
        defaultConfig.put("opensearch", opensearch);

        // Indexing settings
        Map<String, Object> indexing = new HashMap<>();
        indexing.put("batch_size", DEFAULT_BATCH_SIZE);
        indexing.put("directories", new ArrayList<String>());
        indexing.put("file_extensions", List.of(
            ".jpg", ".jpeg", ".png", ".tiff", ".tif",
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf"
        ));
        defaultConfig.put("indexing", indexing);

        // UI settings
        Map<String, Object> ui = new HashMap<>();
        ui.put("thumbnail_size", DEFAULT_THUMBNAIL_SIZE);
        ui.put("results_per_page", 50);
        ui.put("window_width", 1400);
        ui.put("window_height", 900);
        ui.put("theme", "light");
        defaultConfig.put("ui", ui);

        // ExifTool settings
        Map<String, Object> exiftool = new HashMap<>();
        exiftool.put("path", "exiftool");  // Assumes exiftool is in PATH
        exiftool.put("use_for_raw", true);
        defaultConfig.put("exiftool", exiftool);

        // Logging settings
        Map<String, Object> logging = new HashMap<>();
        logging.put("enabled", false);  // Logging disabled by default
        logging.put("level", "INFO");   // DEBUG, INFO, WARN, ERROR
        logging.put("max_log_size_mb", 5);  // Rotate when log exceeds this size
        logging.put("max_log_files", 3);    // Number of rotated log files to keep
        defaultConfig.put("logging", logging);

        // Thumbnail cache settings
        Map<String, Object> cache = new HashMap<>();
        cache.put("enabled", true);        // Disk cache enabled by default
        cache.put("max_size_mb", 500);     // 500 MB default max size
        defaultConfig.put("cache", cache);

        // AI settings (provider selection)
        Map<String, Object> ai = new HashMap<>();
        ai.put("provider", "claude");  // "claude" or "gemini"
        defaultConfig.put("ai", ai);

        // Claude API settings
        Map<String, Object> claude = new HashMap<>();
        claude.put("api_key", "");
        claude.put("model", "claude-sonnet-4-20250514");
        claude.put("analysis_prompt", getDefaultAnalysisPrompt());
        defaultConfig.put("claude", claude);

        // Gemini API settings
        Map<String, Object> gemini = new HashMap<>();
        gemini.put("api_key", "");
        gemini.put("model", "gemini-2.0-flash");
        defaultConfig.put("gemini", gemini);

        // Moondream (local AI) settings
        Map<String, Object> moondream = new HashMap<>();
        moondream.put("python_path", DEFAULT_PYTHON_PATH);
        moondream.put("model", "vikhyatk/moondream2");
        defaultConfig.put("moondream", moondream);

        // Face recognition settings
        Map<String, Object> faces = new HashMap<>();
        faces.put("python_path", "python3");
        faces.put("enabled", true);
        faces.put("confidence_threshold", 0.6);
        faces.put("cluster_threshold", 0.6);
        defaultConfig.put("faces", faces);

        // rclone cloud upload settings
        Map<String, Object> rclone = new HashMap<>();
        rclone.put("rclone_path", "rclone");
        rclone.put("remote_name", "");
        rclone.put("remote_path", "");
        rclone.put("upload_directories", new ArrayList<>());
        defaultConfig.put("rclone", rclone);

        return defaultConfig;
    }

    public synchronized void saveConfig() {
        try {
            // Back up before writing
            if (Files.exists(configPath) && Files.size(configPath) > 0) {
                Files.copy(configPath, configPath.resolveSibling("config.json.bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            objectMapper.writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // OpenSearch settings
    public String getOpenSearchHost() {
        return getNestedString("opensearch", "host", DEFAULT_OPENSEARCH_HOST);
    }

    public void setOpenSearchHost(String host) {
        setNestedValue("opensearch", "host", host);
    }

    public int getOpenSearchPort() {
        return getNestedInt("opensearch", "port", DEFAULT_OPENSEARCH_PORT);
    }

    public void setOpenSearchPort(int port) {
        setNestedValue("opensearch", "port", port);
    }

    public boolean isOpenSearchSsl() {
        return getNestedBoolean("opensearch", "ssl", DEFAULT_OPENSEARCH_SSL);
    }

    public void setOpenSearchSsl(boolean ssl) {
        setNestedValue("opensearch", "ssl", ssl);
    }

    public String getOpenSearchUsername() {
        return getNestedString("opensearch", "username", "");
    }

    public void setOpenSearchUsername(String username) {
        setNestedValue("opensearch", "username", username);
    }

    public String getOpenSearchPassword() {
        return getNestedString("opensearch", "password", "");
    }

    public void setOpenSearchPassword(String password) {
        setNestedValue("opensearch", "password", password);
    }

    public String getIndexName() {
        return getNestedString("opensearch", "index_name", DEFAULT_INDEX_NAME);
    }

    public void setIndexName(String indexName) {
        setNestedValue("opensearch", "index_name", indexName);
    }

    // Indexing settings
    public int getBatchSize() {
        return getNestedInt("indexing", "batch_size", DEFAULT_BATCH_SIZE);
    }

    public void setBatchSize(int batchSize) {
        setNestedValue("indexing", "batch_size", batchSize);
    }

    @SuppressWarnings("unchecked")
    public synchronized List<String> getDirectories() {
        Map<String, Object> indexing = (Map<String, Object>) config.get("indexing");
        if (indexing != null && indexing.containsKey("directories")) {
            return new ArrayList<>((List<String>) indexing.get("directories"));
        }
        return new ArrayList<>();
    }

    public void setDirectories(List<String> directories) {
        setNestedValue("indexing", "directories", new ArrayList<>(directories));
    }

    public void addDirectory(String directory) {
        List<String> directories = getDirectories();
        if (!directories.contains(directory)) {
            directories.add(directory);
            setDirectories(directories);
        }
    }

    public void removeDirectory(String directory) {
        List<String> directories = getDirectories();
        directories.remove(directory);
        setDirectories(directories);
    }

    @SuppressWarnings("unchecked")
    public synchronized List<String> getFileExtensions() {
        Map<String, Object> indexing = (Map<String, Object>) config.get("indexing");
        if (indexing != null && indexing.containsKey("file_extensions")) {
            return new ArrayList<>((List<String>) indexing.get("file_extensions"));
        }
        return List.of(".jpg", ".jpeg", ".png");
    }

    public void setFileExtensions(List<String> extensions) {
        setNestedValue("indexing", "file_extensions", new ArrayList<>(extensions));
    }

    // UI settings
    public int getThumbnailSize() {
        return getNestedInt("ui", "thumbnail_size", DEFAULT_THUMBNAIL_SIZE);
    }

    public void setThumbnailSize(int size) {
        setNestedValue("ui", "thumbnail_size", size);
    }

    public int getResultsPerPage() {
        return getNestedInt("ui", "results_per_page", 50);
    }

    public void setResultsPerPage(int count) {
        setNestedValue("ui", "results_per_page", count);
    }

    public int getWindowWidth() {
        return getNestedInt("ui", "window_width", 1400);
    }

    public void setWindowWidth(int width) {
        setNestedValue("ui", "window_width", width);
    }

    public int getWindowHeight() {
        return getNestedInt("ui", "window_height", 900);
    }

    public void setWindowHeight(int height) {
        setNestedValue("ui", "window_height", height);
    }

    // Theme settings
    public String getTheme() {
        return getNestedString("ui", "theme", "light");
    }

    public void setTheme(String theme) {
        setNestedValue("ui", "theme", theme);
    }

    // ExifTool settings
    public String getExifToolPath() {
        return getNestedString("exiftool", "path", "exiftool");
    }

    public void setExifToolPath(String path) {
        setNestedValue("exiftool", "path", path);
    }

    public boolean isUseExifToolForRaw() {
        return getNestedBoolean("exiftool", "use_for_raw", true);
    }

    // Logging settings
    public boolean isLoggingEnabled() {
        return getNestedBoolean("logging", "enabled", false);
    }

    public void setLoggingEnabled(boolean enabled) {
        setNestedValue("logging", "enabled", enabled);
    }

    public String getLoggingLevel() {
        return getNestedString("logging", "level", "INFO");
    }

    public void setLoggingLevel(String level) {
        setNestedValue("logging", "level", level);
    }

    public int getMaxLogSizeMb() {
        return getNestedInt("logging", "max_log_size_mb", 5);
    }

    public int getMaxLogFiles() {
        return getNestedInt("logging", "max_log_files", 3);
    }

    // Thumbnail cache settings
    public boolean isThumbnailCacheEnabled() {
        return getNestedBoolean("cache", "enabled", true);
    }

    public void setThumbnailCacheEnabled(boolean enabled) {
        setNestedValue("cache", "enabled", enabled);
    }

    public int getThumbnailCacheMaxSizeMB() {
        return getNestedInt("cache", "max_size_mb", 500);
    }

    public void setThumbnailCacheMaxSizeMB(int sizeMB) {
        setNestedValue("cache", "max_size_mb", sizeMB);
    }

    // Claude API settings
    public String getClaudeApiKey() {
        return getNestedString("claude", "api_key", "");
    }

    public void setClaudeApiKey(String apiKey) {
        setNestedValue("claude", "api_key", apiKey);
    }

    public String getClaudeModel() {
        return getNestedString("claude", "model", "claude-sonnet-4-20250514");
    }

    public void setClaudeModel(String model) {
        setNestedValue("claude", "model", model);
    }

    public String getClaudeAnalysisPrompt() {
        return getNestedString("claude", "analysis_prompt", getDefaultAnalysisPrompt());
    }

    public void setClaudeAnalysisPrompt(String prompt) {
        setNestedValue("claude", "analysis_prompt", prompt);
    }

    // AI Provider settings
    public String getAiProvider() {
        return getNestedString("ai", "provider", "claude");
    }

    public void setAiProvider(String provider) {
        setNestedValue("ai", "provider", provider);
    }

    // Gemini API settings
    public String getGeminiApiKey() {
        return getNestedString("gemini", "api_key", "");
    }

    public void setGeminiApiKey(String apiKey) {
        setNestedValue("gemini", "api_key", apiKey);
    }

    public String getGeminiModel() {
        return getNestedString("gemini", "model", "gemini-2.0-flash");
    }

    public void setGeminiModel(String model) {
        setNestedValue("gemini", "model", model);
    }

    // Moondream settings
    private static final String DEFAULT_PYTHON_PATH =
            System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";

    public String getMoondreamPythonPath() {
        return getNestedString("moondream", "python_path", DEFAULT_PYTHON_PATH);
    }

    public void setMoondreamPythonPath(String path) {
        setNestedValue("moondream", "python_path", path);
    }

    public String getMoondreamModel() {
        return getNestedString("moondream", "model", "vikhyatk/moondream2");
    }

    public void setMoondreamModel(String model) {
        setNestedValue("moondream", "model", model);
    }

    // Face recognition settings
    public String getFacesPythonPath() {
        return getNestedString("faces", "python_path", "python3");
    }

    public void setFacesPythonPath(String path) {
        setNestedValue("faces", "python_path", path);
    }

    public boolean isFacesEnabled() {
        return getNestedBoolean("faces", "enabled", true);
    }

    public void setFacesEnabled(boolean enabled) {
        setNestedValue("faces", "enabled", enabled);
    }

    public double getFacesConfidenceThreshold() {
        return getNestedDouble("faces", "confidence_threshold", 0.6);
    }

    public void setFacesConfidenceThreshold(double threshold) {
        setNestedValue("faces", "confidence_threshold", threshold);
    }

    public double getFacesClusterThreshold() {
        return getNestedDouble("faces", "cluster_threshold", 0.6);
    }

    public void setFacesClusterThreshold(double threshold) {
        setNestedValue("faces", "cluster_threshold", threshold);
    }

    // rclone settings
    public String getRclonePath() {
        return getNestedString("rclone", "rclone_path", "rclone");
    }

    public void setRclonePath(String path) {
        setNestedValue("rclone", "rclone_path", path);
    }

    public String getRcloneRemoteName() {
        return getNestedString("rclone", "remote_name", "");
    }

    public void setRcloneRemoteName(String remoteName) {
        setNestedValue("rclone", "remote_name", remoteName);
    }

    public String getRcloneRemotePath() {
        return getNestedString("rclone", "remote_path", "");
    }

    public void setRcloneRemotePath(String remotePath) {
        setNestedValue("rclone", "remote_path", remotePath);
    }

    @SuppressWarnings("unchecked")
    public synchronized List<String> getRcloneUploadDirectories() {
        Map<String, Object> rclone = (Map<String, Object>) config.get("rclone");
        if (rclone != null && rclone.containsKey("upload_directories")) {
            return new ArrayList<>((List<String>) rclone.get("upload_directories"));
        }
        return new ArrayList<>();
    }

    public void setRcloneUploadDirectories(List<String> directories) {
        setNestedValue("rclone", "upload_directories", new ArrayList<>(directories));
    }

    public void addRcloneUploadDirectory(String directory) {
        List<String> directories = getRcloneUploadDirectories();
        if (!directories.contains(directory)) {
            directories.add(directory);
            setRcloneUploadDirectories(directories);
        }
    }

    public void removeRcloneUploadDirectory(String directory) {
        List<String> directories = getRcloneUploadDirectories();
        directories.remove(directory);
        setRcloneUploadDirectories(directories);
    }

    public static String getDefaultAnalysisPrompt() {
        return """
            Analyze this photograph and provide metadata in JSON format. Include:

            1. **tags**: Array of descriptive tags for the image. Include:
               - Photography style (e.g., "Portrait", "Landscape", "Street Photography", "Pet Photography", "Macro", "Architecture", "Food Photography")
               - Subject matter (e.g., "Dog", "Cat", "Bird", "Flower", "Building", "Car")
               - Mood/atmosphere (e.g., "Moody", "Bright", "Dramatic", "Peaceful")
               - Technical aspects if notable (e.g., "Black and White", "Bokeh", "Long Exposure", "HDR")
               - Season/weather if visible (e.g., "Winter", "Snow", "Sunset", "Rainy")
               - Setting (e.g., "Indoor", "Outdoor", "Urban", "Rural", "Beach")

            2. **persons**: Array of descriptive identifiers for people visible in the image. If no people are visible, use an empty array. Don't use names unless they are clearly identifiable public figures. Instead use descriptions like "woman in red dress", "elderly man", "child", etc.

            3. **place**: A single string describing the location if identifiable. This could be a specific place name, city, type of venue (e.g., "Restaurant", "Park", "Beach"), or null if not determinable.

            4. **rating**: Rate the overall quality of the photograph from * to ***** (1 to 5 stars) based on:
               - Composition and framing
               - Technical quality (sharpness, exposure, focus)
               - Artistic value and creativity
               - Color/tonal quality
               - Overall impact and interest

               Use this scale:
               - * = Poor (significant technical issues, bad composition)
               - ** = Below average (noticeable issues, weak composition)
               - *** = Average (decent execution, standard composition)
               - **** = Good (strong composition, good technique, visually appealing)
               - ***** = Excellent (exceptional composition, masterful technique, highly impactful)

            Respond with ONLY valid JSON in this exact format:
            {
                "tags": ["tag1", "tag2", "tag3"],
                "persons": [],
                "place": "Location or null",
                "rating": "***"
            }
            """;
    }

    // Helper methods
    @SuppressWarnings("unchecked")
    private synchronized String getNestedString(String section, String key, String defaultValue) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap != null && sectionMap.containsKey(key)) {
            Object value = sectionMap.get(key);
            return value != null ? value.toString() : defaultValue;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private synchronized int getNestedInt(String section, String key, int defaultValue) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap != null && sectionMap.containsKey(key)) {
            Object value = sectionMap.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private synchronized double getNestedDouble(String section, String key, double defaultValue) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap != null && sectionMap.containsKey(key)) {
            Object value = sectionMap.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private synchronized boolean getNestedBoolean(String section, String key, boolean defaultValue) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap != null && sectionMap.containsKey(key)) {
            Object value = sectionMap.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private synchronized void setNestedValue(String section, String key, Object value) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap == null) {
            sectionMap = new HashMap<>();
            config.put(section, sectionMap);
        }
        sectionMap.put(key, value);
    }

    public Path getConfigPath() {
        return configPath;
    }
}
