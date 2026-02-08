package com.photostat;

import com.photostat.cli.AnalyzeCli;
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
                    "--help".equals(arg) || "-h".equals(arg)) {
                // --help should show CLI help if it's the only arg or paired with a CLI command
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    // Check if a CLI command is also present
                    for (String a : args) {
                        if ("--analyze".equals(a) || "--cache-thumbnails".equals(a)) {
                            return true;
                        }
                    }
                    // If only --help, show general help
                    if (args.length == 1) {
                        printGeneralHelp();
                        System.exit(0);
                    }
                }
                return "--analyze".equals(arg) || "--cache-thumbnails".equals(arg);
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
        boolean isCacheThumbnails = false;
        for (String arg : args) {
            if ("--cache-thumbnails".equals(arg)) {
                isCacheThumbnails = true;
                break;
            }
        }

        if (isCacheThumbnails) {
            ThumbnailCacheCli cli = new ThumbnailCacheCli();
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
        System.out.println("  java -jar photostat.jar --help             Show this help");
        System.out.println();
        System.out.println("For command-specific options:");
        System.out.println("  java -jar photostat.jar --analyze --help");
        System.out.println("  java -jar photostat.jar --cache-thumbnails --help");
    }
}
