package com.photostat.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service for cloud uploads via rclone CLI.
 * Wraps rclone commands using ProcessBuilder.
 */
public class RcloneService {

    private static RcloneService instance;
    private final ConfigService configService;
    private final SidecarService sidecarService;
    private final LoggingService logger;
    private volatile Process currentProcess;

    private RcloneService() {
        this.configService = ConfigService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();
    }

    public static synchronized RcloneService getInstance() {
        if (instance == null) {
            instance = new RcloneService();
        }
        return instance;
    }

    /**
     * Check if rclone is installed and available.
     * Returns the version string, or null if not found.
     */
    public String getVersion() {
        try {
            String rclonePath = configService.getRclonePath();
            ProcessBuilder pb = new ProcessBuilder(rclonePath, "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String firstLine = reader.readLine();
                process.waitFor();
                if (process.exitValue() == 0 && firstLine != null) {
                    return firstLine.trim();
                }
            }
        } catch (Exception e) {
            // rclone not found
        }
        return null;
    }

    /**
     * Check if rclone is installed.
     */
    public boolean isInstalled() {
        return getVersion() != null;
    }

    /**
     * Check if rclone is configured with remote name and upload directories.
     */
    public boolean isConfigured() {
        String remoteName = configService.getRcloneRemoteName();
        List<String> dirs = configService.getRcloneUploadDirectories();
        return remoteName != null && !remoteName.isEmpty() && !dirs.isEmpty();
    }

    /**
     * List available rclone remotes.
     * Returns list of remote names (without trailing colon).
     */
    public List<String> listRemotes() {
        List<String> remotes = new ArrayList<>();
        try {
            String rclonePath = configService.getRclonePath();
            ProcessBuilder pb = new ProcessBuilder(rclonePath, "listremotes");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        // Remove trailing colon (e.g., "gdrive:" -> "gdrive")
                        if (line.endsWith(":")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        remotes.add(line);
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            // rclone not available
        }
        return remotes;
    }

    /**
     * Test connection to a remote by listing its root directory.
     * Returns null on success, or an error message on failure.
     */
    public String testConnection(String remoteName, String remotePath) {
        try {
            String rclonePath = configService.getRclonePath();
            String remote = remoteName + ":" + (remotePath != null ? remotePath : "");
            ProcessBuilder pb = new ProcessBuilder(rclonePath, "lsd", remote, "--max-depth", "1");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stderr for errors
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            // Drain stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return null; // success
            } else {
                String error = stderr.toString().trim();
                return error.isEmpty() ? "Connection failed (exit code " + exitCode + ")" : error;
            }
        } catch (Exception e) {
            return "Failed to run rclone: " + e.getMessage();
        }
    }

    /**
     * Progress information from an rclone upload.
     */
    public static class UploadProgress {
        private final String directory;
        private final String currentFile;
        private final String statusLine;
        private final boolean complete;
        private final String error;

        public UploadProgress(String directory, String currentFile, String statusLine, boolean complete, String error) {
            this.directory = directory;
            this.currentFile = currentFile;
            this.statusLine = statusLine;
            this.complete = complete;
            this.error = error;
        }

        public String getDirectory() { return directory; }
        public String getCurrentFile() { return currentFile; }
        public String getStatusLine() { return statusLine; }
        public boolean isComplete() { return complete; }
        public String getError() { return error; }
    }

    /**
     * Progress information for file-based uploads (selected files).
     */
    public static class FileUploadProgress {
        private final int currentFileIndex;
        private final int totalFiles;
        private final String currentFileName;
        private final String statusLine;
        private final boolean complete;
        private final String error;

        public FileUploadProgress(int currentFileIndex, int totalFiles, String currentFileName,
                                  String statusLine, boolean complete, String error) {
            this.currentFileIndex = currentFileIndex;
            this.totalFiles = totalFiles;
            this.currentFileName = currentFileName;
            this.statusLine = statusLine;
            this.complete = complete;
            this.error = error;
        }

        public int getCurrentFileIndex() { return currentFileIndex; }
        public int getTotalFiles() { return totalFiles; }
        public String getCurrentFileName() { return currentFileName; }
        public String getStatusLine() { return statusLine; }
        public boolean isComplete() { return complete; }
        public String getError() { return error; }
    }

    /**
     * Upload result for a single directory.
     */
    public static class UploadResult {
        private final String directory;
        private final boolean success;
        private final String output;
        private final String error;

        public UploadResult(String directory, boolean success, String output, String error) {
            this.directory = directory;
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public String getDirectory() { return directory; }
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }
    }

    /**
     * Upload a single directory to the configured remote.
     */
    public UploadResult uploadDirectory(String localDir, String remoteName, String remotePath,
                                         boolean dryRun, Consumer<UploadProgress> progressCallback) {
        try {
            File dir = new File(localDir);
            if (!dir.exists() || !dir.isDirectory()) {
                return new UploadResult(localDir, false, "", "Directory does not exist: " + localDir);
            }

            String rclonePath = configService.getRclonePath();
            String remote = remoteName + ":" + (remotePath != null && !remotePath.isEmpty() ? remotePath : "");

            List<String> command = new ArrayList<>();
            command.add(rclonePath);
            command.add("copy");
            command.add(localDir);
            command.add(remote);
            command.add("--exclude");
            command.add("*.photostat.json");
            command.add("-v");
            command.add("--stats-log-level");
            command.add("NOTICE");
            if (dryRun) {
                command.add("--dry-run");
            }

            // Log the command
            logger.info("RcloneService", "Running: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            currentProcess = process;

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        output.append(line).append("\n");
                        if (progressCallback != null) {
                            progressCallback.accept(new UploadProgress(localDir, null, line, false, null));
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            currentProcess = null;

            if (exitCode == 0) {
                if (progressCallback != null) {
                    progressCallback.accept(new UploadProgress(localDir, null, "Complete", true, null));
                }
                return new UploadResult(localDir, true, output.toString(), null);
            } else {
                String error = "rclone exited with code " + exitCode;
                if (progressCallback != null) {
                    progressCallback.accept(new UploadProgress(localDir, null, error, true, error));
                }
                return new UploadResult(localDir, false, output.toString(), error);
            }
        } catch (Exception e) {
            currentProcess = null;
            String error = "Failed to run rclone: " + e.getMessage();
            if (progressCallback != null) {
                progressCallback.accept(new UploadProgress(localDir, null, error, true, error));
            }
            return new UploadResult(localDir, false, "", error);
        }
    }

    /**
     * Upload all configured directories.
     * Returns a list of results, one per directory.
     */
    public List<UploadResult> uploadAll(boolean dryRun, Consumer<UploadProgress> progressCallback) {
        List<UploadResult> results = new ArrayList<>();
        String remoteName = configService.getRcloneRemoteName();
        String remotePath = configService.getRcloneRemotePath();
        List<String> directories = configService.getRcloneUploadDirectories();

        for (String dir : directories) {
            UploadResult result = uploadDirectory(dir, remoteName, remotePath, dryRun, progressCallback);
            results.add(result);
        }

        return results;
    }

    /**
     * Upload specific files to a remote, one at a time for reliable progress tracking.
     * Uses rclone copyto for each file so we know exactly when each one completes.
     */
    public UploadResult uploadFiles(List<String> filePaths, String remoteName, String remotePath,
                                     boolean dryRun, Consumer<FileUploadProgress> progressCallback) {
        int totalFiles = filePaths.size();
        StringBuilder allOutput = new StringBuilder();
        List<String> errors = new ArrayList<>();
        int filesCompleted = 0;

        String rclonePath = configService.getRclonePath();
        String remoteBase = remoteName + ":" + (remotePath != null && !remotePath.isEmpty() ? remotePath : "");

        for (int i = 0; i < filePaths.size(); i++) {
            String filePath = filePaths.get(i);
            Path path = Path.of(filePath);
            String fileName = path.getFileName().toString();

            // Report starting this file
            if (progressCallback != null) {
                progressCallback.accept(new FileUploadProgress(
                        i, totalFiles, fileName,
                        "Uploading: " + fileName, false, null));
            }

            try {
                // Use copyto to upload a single file: rclone copyto <local> <remote>/<filename>
                String remoteDest = remoteBase.endsWith(":") || remoteBase.endsWith("/")
                        ? remoteBase + fileName
                        : remoteBase + "/" + fileName;

                List<String> command = new ArrayList<>();
                command.add(rclonePath);
                command.add("copyto");
                command.add(filePath);
                command.add(remoteDest);
                command.add("-v");
                command.add("--stats-log-level");
                command.add("NOTICE");
                if (dryRun) {
                    command.add("--dry-run");
                }

                logger.info("RcloneService", "Running: " + String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentProcess = process;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            allOutput.append(line).append("\n");
                            if (progressCallback != null) {
                                progressCallback.accept(new FileUploadProgress(
                                        i, totalFiles, fileName,
                                        line, false, null));
                            }
                        }
                    }
                }

                int exitCode = process.waitFor();
                currentProcess = null;

                if (exitCode == 0) {
                    filesCompleted++;
                    if (!dryRun) {
                        sidecarService.addCloudUpload(filePath, remoteName);
                    }
                    if (progressCallback != null) {
                        progressCallback.accept(new FileUploadProgress(
                                filesCompleted, totalFiles, fileName,
                                "Uploaded: " + fileName, false, null));
                    }
                } else {
                    String error = fileName + ": rclone exited with code " + exitCode;
                    errors.add(error);
                    logger.warn("RcloneService", error);
                    if (progressCallback != null) {
                        progressCallback.accept(new FileUploadProgress(
                                i, totalFiles, fileName,
                                "Failed: " + fileName, false, error));
                    }
                }
            } catch (Exception e) {
                currentProcess = null;
                String error = fileName + ": " + e.getMessage();
                errors.add(error);
                logger.error("RcloneService", "Failed to upload " + filePath, e);
            }
        }

        boolean success = errors.isEmpty();
        String errorStr = errors.isEmpty() ? null : String.join("\n", errors);

        if (progressCallback != null) {
            progressCallback.accept(new FileUploadProgress(
                    totalFiles, totalFiles, null,
                    "Complete", true, errorStr));
        }

        return new UploadResult("selected files", success, allOutput.toString(), errorStr);
    }

    /**
     * Cancel the currently running rclone process.
     */
    public void cancelUpload() {
        Process process = currentProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
