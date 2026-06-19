package com.photostat;

import com.photostat.cli.AestheticScoreCli;
import com.photostat.cli.AnalyzeCli;
import com.photostat.cli.DuplicatesCli;
import com.photostat.cli.FaceDetectCli;
import com.photostat.cli.RcloneUploadCli;
import com.photostat.cli.ThumbnailCacheCli;

/**
 * Launcher class for creating executable JAR.
 *
 * JavaFX requires this workaround because the module system prevents
 * launching an Application class directly from a shaded JAR.
 * This class doesn't extend Application, so it bypasses that restriction.
 *
 * This class also handles CLI mode detection - if --analyze is passed,
 * it runs the CLI analyzer instead of launching the GUI.
 */
public class Launcher {
    public static void main(String[] args) {
        // Check for CLI mode
        if (isCliMode(args)) {
            runCli(args);
        } else {
            // Launch GUI
            App.main(args);
        }
    }

    /**
     * Check if CLI mode is requested via command-line arguments.
     */
    private static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if ("--analyze".equals(arg) || "--cache-thumbnails".equals(arg) ||
                    "--find-duplicates".equals(arg) || "--detect-faces".equals(arg) ||
                    "--rclone-upload".equals(arg) || "--score-aesthetics".equals(arg) ||
                    "--help".equals(arg) || "-h".equals(arg)) {
                // --help should show CLI help if it's the only arg or paired with a CLI command
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    // Check if a CLI command is also present
                    for (String a : args) {
                        if ("--analyze".equals(a) || "--cache-thumbnails".equals(a) ||
                                "--find-duplicates".equals(a) || "--detect-faces".equals(a) ||
                                "--rclone-upload".equals(a) || "--score-aesthetics".equals(a)) {
                            return true;
                        }
                    }
                    // If only --help, show general help
                    if (args.length == 1) {
                        printGeneralHelp();
                        System.exit(0);
                    }
                }
                return "--analyze".equals(arg) || "--cache-thumbnails".equals(arg) ||
                        "--find-duplicates".equals(arg) || "--detect-faces".equals(arg) ||
                        "--rclone-upload".equals(arg) || "--score-aesthetics".equals(arg);
            }
        }
        return false;
    }

    /**
     * Run the appropriate CLI based on the command.
     */
    private static void runCli(String[] args) {
        int exitCode;

        // Determine which CLI to run
        String command = null;
        for (String arg : args) {
            if ("--cache-thumbnails".equals(arg) || "--find-duplicates".equals(arg) ||
                    "--analyze".equals(arg) || "--detect-faces".equals(arg) ||
                    "--rclone-upload".equals(arg) || "--score-aesthetics".equals(arg)) {
                command = arg;
                break;
            }
        }

        if ("--rclone-upload".equals(command)) {
            RcloneUploadCli cli = new RcloneUploadCli();
            exitCode = cli.run(args);
        } else if ("--score-aesthetics".equals(command)) {
            AestheticScoreCli cli = new AestheticScoreCli();
            exitCode = cli.run(args);
        } else if ("--cache-thumbnails".equals(command)) {
            ThumbnailCacheCli cli = new ThumbnailCacheCli();
            exitCode = cli.run(args);
        } else if ("--find-duplicates".equals(command)) {
            DuplicatesCli cli = new DuplicatesCli();
            exitCode = cli.run(args);
        } else if ("--detect-faces".equals(command)) {
            FaceDetectCli cli = new FaceDetectCli();
            exitCode = cli.run(args);
        } else {
            AnalyzeCli cli = new AnalyzeCli();
            exitCode = cli.run(args);
        }

        System.exit(exitCode);
    }

    /**
     * Print general help about available modes.
     */
    private static void printGeneralHelp() {
        System.out.println("PhotoStat - Photo Library Manager");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar photostat.jar                    Launch GUI application");
        System.out.println("  java -jar photostat.jar --analyze          Run CLI batch analysis");
        System.out.println("  java -jar photostat.jar --cache-thumbnails Pre-generate thumbnail cache");
        System.out.println("  java -jar photostat.jar --find-duplicates  Find duplicate images");
        System.out.println("  java -jar photostat.jar --detect-faces     Detect and cluster faces");
        System.out.println("  java -jar photostat.jar --score-aesthetics Score image quality/aesthetics");
        System.out.println("  java -jar photostat.jar --rclone-upload    Upload to cloud via rclone");
        System.out.println("  java -jar photostat.jar --help             Show this help");
        System.out.println();
        System.out.println("For command-specific options:");
        System.out.println("  java -jar photostat.jar --analyze --help");
        System.out.println("  java -jar photostat.jar --cache-thumbnails --help");
        System.out.println("  java -jar photostat.jar --find-duplicates --help");
        System.out.println("  java -jar photostat.jar --detect-faces --help");
        System.out.println("  java -jar photostat.jar --score-aesthetics --help");
        System.out.println("  java -jar photostat.jar --rclone-upload --help");
    }
}
