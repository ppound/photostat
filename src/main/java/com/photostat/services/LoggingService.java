package com.photostat.services;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple file-based logging service for PhotoStat.
 * Logs are written to ~/.photostat/photostat.log
 * Logging can be enabled/disabled via config.json
 */
public class LoggingService {

    private static LoggingService instance;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private Path logFile;
    private PrintWriter writer;
    private boolean enabled;
    private String logLevel;

    private LoggingService() {
        // Create .photostat directory in user home
        String userHome = System.getProperty("user.home");
        Path photostatDir = Paths.get(userHome, ".photostat");

        try {
            Files.createDirectories(photostatDir);
            logFile = photostatDir.resolve("photostat.log");

            // Check if logging is enabled (read config directly to avoid circular dependency)
            Path configPath = photostatDir.resolve("config.json");
            enabled = readLoggingEnabledFromConfig(configPath);
            logLevel = readLoggingLevelFromConfig(configPath);

            if (enabled) {
                // Append mode
                writer = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
                writeLog("INFO", "LoggingService", "=== PhotoStat Started ===");
                writeLog("INFO", "LoggingService", "Log file: " + logFile.toAbsolutePath());
                writeLog("INFO", "LoggingService", "Log level: " + logLevel);
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize logging: " + e.getMessage());
            enabled = false;
            logFile = photostatDir.resolve("photostat.log");
        }
    }

    private boolean readLoggingEnabledFromConfig(Path configPath) {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                // Simple parsing - look for "enabled" : true/false in logging section
                if (content.contains("\"logging\"") && content.contains("\"enabled\"")) {
                    int loggingIdx = content.indexOf("\"logging\"");
                    int enabledIdx = content.indexOf("\"enabled\"", loggingIdx);
                    if (enabledIdx > loggingIdx) {
                        String afterEnabled = content.substring(enabledIdx + 10, Math.min(enabledIdx + 30, content.length()));
                        return afterEnabled.contains("true");
                    }
                }
            }
        } catch (Exception e) {
            // Ignore, return default
        }
        return false;  // Default: logging disabled
    }

    private String readLoggingLevelFromConfig(Path configPath) {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                if (content.contains("\"logging\"") && content.contains("\"level\"")) {
                    int loggingIdx = content.indexOf("\"logging\"");
                    int levelIdx = content.indexOf("\"level\"", loggingIdx);
                    if (levelIdx > loggingIdx) {
                        int startQuote = content.indexOf("\"", levelIdx + 7);
                        int endQuote = content.indexOf("\"", startQuote + 1);
                        if (startQuote > 0 && endQuote > startQuote) {
                            return content.substring(startQuote + 1, endQuote).toUpperCase();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore, return default
        }
        return "INFO";  // Default level
    }

    public static synchronized LoggingService getInstance() {
        if (instance == null) {
            instance = new LoggingService();
        }
        return instance;
    }

    public void info(String component, String message) {
        if (shouldLog("INFO")) {
            writeLog("INFO", component, message);
        }
    }

    public void warn(String component, String message) {
        if (shouldLog("WARN")) {
            writeLog("WARN", component, message);
        }
    }

    public void error(String component, String message) {
        if (shouldLog("ERROR")) {
            writeLog("ERROR", component, message);
        }
    }

    public void error(String component, String message, Throwable throwable) {
        if (shouldLog("ERROR")) {
            writeLog("ERROR", component, message);
            if (throwable != null && writer != null) {
                synchronized (this) {
                    throwable.printStackTrace(writer);
                    writer.flush();
                }
            }
        }
    }

    public void debug(String component, String message) {
        if (shouldLog("DEBUG")) {
            writeLog("DEBUG", component, message);
        }
    }

    private boolean shouldLog(String level) {
        if (!enabled) return false;

        int levelPriority = getLevelPriority(level);
        int configPriority = getLevelPriority(logLevel);
        return levelPriority >= configPriority;
    }

    private int getLevelPriority(String level) {
        switch (level.toUpperCase()) {
            case "DEBUG": return 0;
            case "INFO": return 1;
            case "WARN": return 2;
            case "ERROR": return 3;
            default: return 1;
        }
    }

    private void writeLog(String level, String component, String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logLine = String.format("[%s] [%s] [%s] %s", timestamp, level, component, message);

        // Write to file only (not console) when enabled
        if (writer != null) {
            synchronized (this) {
                writer.println(logLine);
                writer.flush();
            }
        }
    }

    public Path getLogFilePath() {
        return logFile;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void close() {
        if (writer != null && enabled) {
            writeLog("INFO", "LoggingService", "=== PhotoStat Shutdown ===");
            writer.close();
        }
    }
}
