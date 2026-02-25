package com.photostat.cli;

import com.photostat.services.ConfigService;
import com.photostat.services.RcloneService;

import java.util.List;

/**
 * Command-line interface for cloud uploads via rclone.
 * Does not require OpenSearch — just runs rclone.
 */
public class RcloneUploadCli {

    private final ConfigService configService;
    private final RcloneService rcloneService;

    private boolean dryRun = false;
    private boolean quiet = false;
    private String specificDir = null;

    public RcloneUploadCli() {
        this.configService = ConfigService.getInstance();
        this.rcloneService = RcloneService.getInstance();
    }

    public int run(String[] args) {
        if (!parseArgs(args)) {
            return 1;
        }

        // Check rclone is installed
        String version = rcloneService.getVersion();
        if (version == null) {
            System.err.println("Error: rclone is not installed or not found at: " + configService.getRclonePath());
            System.err.println("Install rclone from https://rclone.org/downloads/");
            return 1;
        }

        if (!quiet) {
            System.out.println("PhotoStat CLI - Cloud Upload via rclone");
            System.out.println("========================================");
            System.out.println("rclone:  " + version);
        }

        // Get remote info
        String remoteName = configService.getRcloneRemoteName();
        String remotePath = configService.getRcloneRemotePath();

        if (remoteName == null || remoteName.isEmpty()) {
            System.err.println("Error: No remote name configured.");
            System.err.println("Configure in Settings > Cloud Upload or edit ~/.photostat/config.json");
            return 1;
        }

        String remote = remoteName + ":" + (remotePath != null && !remotePath.isEmpty() ? remotePath : "");

        // Get directories
        List<String> directories;
        if (specificDir != null) {
            java.io.File dir = new java.io.File(specificDir);
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("Error: Directory does not exist: " + specificDir);
                return 1;
            }
            directories = List.of(specificDir);
        } else {
            directories = configService.getRcloneUploadDirectories();
        }

        if (directories.isEmpty()) {
            System.err.println("Error: No upload directories configured.");
            System.err.println("Configure in Settings > Cloud Upload or edit ~/.photostat/config.json");
            return 1;
        }

        if (!quiet) {
            System.out.println("Remote:  " + remote);
            System.out.println("Dirs:    " + directories.size());
            if (dryRun) {
                System.out.println("Mode:    DRY RUN (no files will be uploaded)");
            }
            System.out.println();
        }

        // Upload each directory
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < directories.size(); i++) {
            String dir = directories.get(i);
            if (!quiet) {
                System.out.println("[" + (i + 1) + "/" + directories.size() + "] Uploading: " + dir + " -> " + remote);
            }

            RcloneService.UploadResult result = rcloneService.uploadDirectory(
                    dir, remoteName, remotePath, dryRun, progress -> {
                        if (!quiet && progress.getStatusLine() != null && !progress.getStatusLine().isEmpty()) {
                            System.out.println("  " + progress.getStatusLine());
                        }
                    });

            if (result.isSuccess()) {
                successCount++;
                if (!quiet) {
                    System.out.println("  Done.");
                }
            } else {
                failCount++;
                System.err.println("  FAILED: " + result.getError());
            }

            if (!quiet && i < directories.size() - 1) {
                System.out.println();
            }
        }

        // Summary
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSeconds = elapsed / 1000.0;

        if (!quiet) {
            System.out.println();
            System.out.println("========================================");
            System.out.println("Upload Complete" + (dryRun ? " (DRY RUN)" : ""));
            System.out.println("========================================");
            System.out.println("Directories: " + directories.size());
            System.out.println("Succeeded:   " + successCount);
            System.out.println("Failed:      " + failCount);
            System.out.printf("Time:        %.1f seconds%n", elapsedSeconds);
        }

        return failCount > 0 ? 1 : 0;
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--rclone-upload":
                    break;
                case "--dir":
                    if (i + 1 < args.length) {
                        specificDir = args[++i];
                    } else {
                        System.err.println("Error: --dir requires a path argument");
                        printUsage();
                        return false;
                    }
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--quiet":
                case "-q":
                    quiet = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return false;
                default:
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        printUsage();
                        return false;
                    }
                    break;
            }
        }
        return true;
    }

    private void printUsage() {
        System.out.println("PhotoStat CLI - Cloud Upload via rclone");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --rclone-upload [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dir <path>           Upload specific directory (overrides config)");
        System.out.println("  --dry-run              Show what would be uploaded without uploading");
        System.out.println("  --quiet, -q            Minimal output");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Configuration: ~/.photostat/config.json (rclone section)");
        System.out.println();
        System.out.println("Prerequisites:");
        System.out.println("  1. Install rclone: https://rclone.org/downloads/");
        System.out.println("  2. Configure remote: rclone config");
        System.out.println("  3. Set remote name and upload dirs in PhotoStat settings");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar photostat.jar --rclone-upload");
        System.out.println("  java -jar photostat.jar --rclone-upload --dry-run");
        System.out.println("  java -jar photostat.jar --rclone-upload --dir /path/to/photos");
    }
}
