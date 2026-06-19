package com.photostat.cli;

import com.photostat.services.AestheticService;
import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-line interface for batch aesthetic/quality scoring of indexed images.
 *
 * <p>Scores are written to the {@code aesthetic_score} field in OpenSearch (a
 * 0..1 value, 1.0 = best), leaving the user-authored {@code rating} untouched.
 * Scoring runs against the Dockerized aesthetic backend (port 8003).
 */
public class AestheticScoreCli {

    private final ConfigService configService;
    private final AestheticService aestheticService;
    private final OpenSearchService openSearchService;

    // Formats the IQA backend (Pillow) can decode.
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tiff", ".tif", ".gif"
    );

    private boolean dryRun = false;
    private boolean force = false;
    private boolean quiet = false;
    private boolean showProgress = true;
    private String specificDir = null;
    private int batchSize = 16;

    public AestheticScoreCli() {
        this.configService = ConfigService.getInstance();
        this.aestheticService = AestheticService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
    }

    public int run(String[] args) {
        if (!parseArgs(args)) {
            return 1;
        }

        if (!quiet) {
            printConfig();
        }

        // Check the backend is reachable and report the device it loaded on.
        if (!quiet) {
            System.out.print("Checking aesthetic backend... ");
        }
        String health;
        try {
            health = aestheticService.getHealthInfo();
        } catch (Exception e) {
            System.err.println("FAILED");
            System.err.println("Error: aesthetic service not reachable at "
                    + configService.getAestheticEndpoint());
            System.err.println("Start it with: cd docker && docker compose "
                    + "-f docker-compose.yml -f docker-compose.gpu.yml up -d aesthetic");
            return 1;
        }
        if (!quiet) {
            boolean gpu = health.replaceAll("\\s+", "").contains("\"device\":\"cuda\"");
            System.out.println("OK");
            System.out.println("Execution:   " + (gpu ? "GPU (CUDA)" : "CPU"));
            System.out.println("Backend:     " + health);
        }

        // Connect to OpenSearch and gather indexed image paths.
        if (!connectToOpenSearch()) {
            System.err.println("Error: Failed to connect to OpenSearch.");
            return 1;
        }

        // Make sure the aesthetic_score field is explicitly mapped (float) before
        // we write any scores, so a pre-existing index doesn't fall back to
        // dynamic type inference. Additive and idempotent — safe every run.
        try {
            openSearchService.ensureMapping(configService.getIndexName());
        } catch (Exception e) {
            System.err.println("Warning: could not ensure mapping: " + e.getMessage());
        }

        if (!quiet) {
            System.out.println("\nFetching image paths from OpenSearch...");
        }

        List<String> imagePaths = new ArrayList<>();
        try {
            for (String p : openSearchService.searchAllFilePaths(1000)) {
                String lower = p.toLowerCase();
                boolean supported = false;
                for (String ext : SUPPORTED_FORMATS) {
                    if (lower.endsWith(ext)) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) continue;
                if (specificDir != null && !p.startsWith(specificDir)) continue;
                imagePaths.add(p);
            }
        } catch (Exception e) {
            System.err.println("Error fetching file paths: " + e.getMessage());
            return 1;
        }

        if (imagePaths.isEmpty()) {
            System.out.println("No supported indexed images found"
                    + (specificDir != null ? " under " + specificDir : "") + ".");
            return 0;
        }

        if (!quiet) {
            System.out.println("Found " + imagePaths.size() + " indexed images"
                    + (force ? " (force: rescoring all)" : " (will skip already-scored)"));
        }

        if (dryRun) {
            System.out.println("\n[DRY RUN] Would score up to " + imagePaths.size()
                    + " images in batches of " + batchSize + ".");
            return 0;
        }

        long start = System.currentTimeMillis();
        AtomicLong lastProgress = new AtomicLong(0);
        int scored;
        try {
            scored = aestheticService.scoreCollection(imagePaths, force, batchSize,
                    (processed, total) -> {
                        if (showProgress) {
                            long now = System.currentTimeMillis();
                            if (now - lastProgress.get() >= 500 || processed.equals(total)) {
                                lastProgress.set(now);
                                synchronized (System.out) {
                                    System.out.printf("\r[%d/%d] Scoring... %.0f%%",
                                            processed, total, (processed * 100.0) / total);
                                    System.out.flush();
                                }
                            }
                        }
                    });
        } catch (Exception e) {
            System.err.println("\nError during scoring: " + e.getMessage());
            return 1;
        }

        long elapsed = System.currentTimeMillis() - start;
        if (!quiet) {
            System.out.println();
            System.out.println("=====================================");
            System.out.println("Aesthetic Scoring Complete");
            System.out.println("=====================================");
            System.out.printf("Scored: %d images in %.1f seconds%n", scored, elapsed / 1000.0);
            System.out.println("Field:  aesthetic_score (0..1, 1.0 = best)");
            System.out.println("Sort/filter on it in the GUI to surface your top photos.");
        }
        return 0;
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--score-aesthetics":
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
                case "--batch":
                    if (i + 1 < args.length) {
                        try {
                            batchSize = Integer.parseInt(args[++i]);
                            if (batchSize < 1 || batchSize > 256) {
                                System.err.println("Error: --batch must be between 1 and 256");
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Error: --batch requires a number");
                            return false;
                        }
                    } else {
                        System.err.println("Error: --batch requires a number (1-256)");
                        printUsage();
                        return false;
                    }
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--force":
                    force = true;
                    break;
                case "--quiet":
                case "-q":
                    quiet = true;
                    showProgress = false;
                    break;
                case "--no-progress":
                    showProgress = false;
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
        System.out.println("PhotoStat CLI - Aesthetic / Quality Scoring");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --score-aesthetics [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dir <path>     Only score indexed images whose path starts with <path>");
        System.out.println("  --batch <n>      Images per backend call (1-256, default: 16)");
        System.out.println("  --dry-run        Show what would be done without scoring");
        System.out.println("  --force          Rescore images that already have a score");
        System.out.println("  --quiet, -q      Minimal output");
        System.out.println("  --no-progress    Disable progress updates");
        System.out.println("  --help, -h       Show this help");
        System.out.println();
        System.out.println("Prerequisites:");
        System.out.println("  Start the aesthetic backend (port 8003):");
        System.out.println("    cd docker && docker compose -f docker-compose.yml \\");
        System.out.println("        -f docker-compose.gpu.yml up -d aesthetic");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - Operates on already-indexed images; writes the aesthetic_score field.");
        System.out.println("  - Scoring is incremental: already-scored images are skipped unless --force.");
        System.out.println("  - The user-authored 'rating' field is never modified.");
    }

    private void printConfig() {
        System.out.println("PhotoStat CLI - Aesthetic Scoring");
        System.out.println("=====================================");
        System.out.println("Endpoint:  " + configService.getAestheticEndpoint());
        System.out.println("Metric:    " + configService.getAestheticMetric());
        System.out.println("Batch:     " + batchSize);
        System.out.println("Config:    " + configService.getConfigPath());
    }

    private boolean connectToOpenSearch() {
        try {
            openSearchService.connect();
            return openSearchService.testConnection();
        } catch (Exception e) {
            return false;
        }
    }
}
