package com.photostat.cli;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;

import java.util.List;

/**
 * CLI for finding duplicate images in the indexed collection.
 * Queries OpenSearch for images sharing the same content or perceptual hash.
 */
public class DuplicatesCli {

    private final ConfigService configService;
    private final OpenSearchService openSearchService;

    private String mode = "exact";
    private boolean quiet = false;

    public DuplicatesCli() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
    }

    public int run(String[] args) {
        if (!parseArgs(args)) {
            return 1;
        }

        if (!quiet) {
            System.out.println("PhotoStat CLI - Duplicate Finder");
            System.out.println("=================================");
            System.out.println("Mode: " + ("exact".equals(mode) ? "Exact (SHA-256)" : "Visual (perceptual hash)"));
        }

        // Connect to OpenSearch
        try {
            if (!openSearchService.isConnected()) {
                if (!quiet) System.out.println("Connecting to OpenSearch...");
                openSearchService.connect();
            }

            if (!openSearchService.testConnection()) {
                System.err.println("Error: Failed to connect to OpenSearch.");
                return 1;
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        // Find duplicates
        try {
            String hashField = "exact".equals(mode) ? "content_hash" : "perceptual_hash";
            List<OpenSearchService.DuplicateGroup> groups = openSearchService.findDuplicates(hashField);

            if (groups.isEmpty()) {
                System.out.println("\nNo duplicates found.");
                return 0;
            }

            // Summary
            int totalImages = groups.stream().mapToInt(OpenSearchService.DuplicateGroup::getCount).sum();
            long reclaimableBytes = groups.stream().mapToLong(OpenSearchService.DuplicateGroup::getReclaimableSize).sum();

            System.out.println("\nFound " + groups.size() + " duplicate groups (" +
                    totalImages + " images, " + formatSize(reclaimableBytes) + " reclaimable)");
            System.out.println();

            // Print each group
            int groupNum = 1;
            for (OpenSearchService.DuplicateGroup group : groups) {
                System.out.println("--- Group " + groupNum + " (hash: " +
                        group.getHash().substring(0, Math.min(16, group.getHash().length())) + "...) ---");

                for (ImageMetadata img : group.getImages()) {
                    String size = img.getFileSize() != null ? img.getFileSizeString() : "?";
                    String date = img.getDateTaken() != null ? img.getDateTaken().toString() : "no date";
                    System.out.println("  " + img.getFilePath() + "  [" + size + ", " + date + "]");
                }

                System.out.println("  Reclaimable: " + formatSize(group.getReclaimableSize()));
                System.out.println();
                groupNum++;
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error finding duplicates: " + e.getMessage());
            return 1;
        }
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--find-duplicates":
                    break;
                case "--mode":
                    if (i + 1 < args.length) {
                        mode = args[++i].toLowerCase();
                        if (!"exact".equals(mode) && !"visual".equals(mode)) {
                            System.err.println("Error: --mode must be 'exact' or 'visual'");
                            return false;
                        }
                    } else {
                        System.err.println("Error: --mode requires 'exact' or 'visual'");
                        printUsage();
                        return false;
                    }
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
        System.out.println("PhotoStat CLI - Duplicate Finder");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --find-duplicates [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --mode <exact|visual>  Detection mode (default: exact)");
        System.out.println("                         exact  = SHA-256 byte-for-byte match");
        System.out.println("                         visual = perceptual hash (resized/recompressed)");
        System.out.println("  --quiet, -q            Minimal output");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Note: Images must be indexed first. Hashes are computed during indexing.");
        System.out.println("Re-index your collection to populate hash fields for existing images.");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
