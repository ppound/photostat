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
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
                config = createDefaultConfig();
            }
        } else {
            config = createDefaultConfig();
            saveConfig();
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
        defaultConfig.put("ui", ui);

        // ExifTool settings
        Map<String, Object> exiftool = new HashMap<>();
        exiftool.put("path", "exiftool");  // Assumes exiftool is in PATH
        exiftool.put("use_for_raw", true);
        defaultConfig.put("exiftool", exiftool);

        return defaultConfig;
    }

    public void saveConfig() {
        try {
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
    public List<String> getDirectories() {
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
    public List<String> getFileExtensions() {
        Map<String, Object> indexing = (Map<String, Object>) config.get("indexing");
        if (indexing != null && indexing.containsKey("file_extensions")) {
            return (List<String>) indexing.get("file_extensions");
        }
        return List.of(".jpg", ".jpeg", ".png");
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

    // Helper methods
    @SuppressWarnings("unchecked")
    private String getNestedString(String section, String key, String defaultValue) {
        Map<String, Object> sectionMap = (Map<String, Object>) config.get(section);
        if (sectionMap != null && sectionMap.containsKey(key)) {
            Object value = sectionMap.get(key);
            return value != null ? value.toString() : defaultValue;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private int getNestedInt(String section, String key, int defaultValue) {
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
    private boolean getNestedBoolean(String section, String key, boolean defaultValue) {
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
    private void setNestedValue(String section, String key, Object value) {
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
