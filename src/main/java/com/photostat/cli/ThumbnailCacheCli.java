package com.photostat.cli;

import com.photostat.services.ConfigService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CLI for pre-caching image thumbnails.
 * Generates thumbnails for all indexed images up to the cache size limit.
 * Fetches image list from OpenSearch, then generates thumbnails in parallel.
 */
public class ThumbnailCacheCli {

    private final ConfigService configService;
    private final OpenSearchService openSearchService;
    private final ThumbnailService thumbnailService;

    private boolean quiet = false;
    private boolean dryRun = false;
    private int parallelThreads = 4;

    private final Set<String> supportedExtensions;

    public ThumbnailCacheCli() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.thumbnailService = ThumbnailService.getInstance();
        this.supportedExtensions = new HashSet<>(configService.getFileExtensions());
    }

    public int run(String[] args) {
        parseArgs(args);

        if (!quiet) {
            System.out.println("PhotoStat CLI - Thumbnail Cache Generator");
            System.out.println("==========================================");
        }

        // Initialize JavaFX toolkit (required for image processing)
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized, ignore
        }

        try {
            // Connect to OpenSearch
            if (!openSearchService.isConnected()) {
                if (!quiet) System.out.println("Connecting to OpenSearch...");
                openSearchService.connect();
            }

            // Get cache configuration
            long maxCacheSizeBytes = configService.getThumbnailCacheMaxSizeMB() * 1024L * 1024L;
            long currentCacheSize = thumbnailService.getDiskCacheSize();
            int currentCacheCount = thumbnailService.getDiskCacheFileCount();

            if (!quiet) {
                System.out.println("Cache directory: " + thumbnailService.getDiskCacheDir());
                System.out.println("Max cache size: " + configService.getThumbnailCacheMaxSizeMB() + " MB");
                System.out.println("Current cache: " + currentCacheCount + " files (" +
                        (currentCacheSize / 1024 / 1024) + " MB)");
                System.out.println("Parallel threads: " + parallelThreads);
                System.out.println();
            }

            // Get total document count
            long totalDocuments = openSearchService.getDocumentCount();
            if (!quiet) {
                System.out.println("Total indexed images: " + totalDocuments);
            }

            if (totalDocuments == 0) {
                System.out.println("No images indexed. Run indexing first.");
                return 0;
            }

            // Estimate available space
            long estimatedThumbnailSize = 35 * 1024; // 35KB average
            long availableSpace = maxCacheSizeBytes - currentCacheSize;
            long estimatedNewThumbnails = availableSpace / estimatedThumbnailSize;

            if (!quiet) {
                System.out.println("Available cache space: " + (availableSpace / 1024 / 1024) + " MB");
                System.out.println("Estimated thumbnails that can be cached: ~" + estimatedNewThumbnails);
                System.out.println();
            }

            if (availableSpace < estimatedThumbnailSize) {
                System.out.println("Cache is full. Increase cache size in settings or clear existing cache.");
                return 0;
            }

            if (dryRun) {
                System.out.println("[DRY RUN] Would generate thumbnails for up to " +
                        Math.min(totalDocuments, estimatedNewThumbnails) + " images.");
                return 0;
            }

            // Fetch all file paths from OpenSearch using search_after pagination
            if (!quiet) {
                System.out.println("Fetching file paths from OpenSearch...");
            }
            List<String> allFilePaths = openSearchService.searchAllFilePaths(1000);
            final long totalDocs = allFilePaths.size();

            if (!quiet) {
                System.out.println("Found " + totalDocs + " file paths.");
                System.out.println("Generating thumbnails with " + parallelThreads + " threads...");
                System.out.println();
            }

            // Create thread pool for parallel thumbnail generation
            ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);

            // Atomic counters for thread-safe tracking
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger cached = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            AtomicBoolean cacheFull = new AtomicBoolean(false);
            AtomicLong lastProgressUpdate = new AtomicLong(0);

            long startTime = System.currentTimeMillis();

            // Process all file paths in parallel using CompletionService to avoid
            // holding all futures in memory simultaneously for large collections.
            ExecutorCompletionService<Void> completionService =
                    new ExecutorCompletionService<>(executor);
            int submitted = 0;

            for (String filePath : allFilePaths) {
                if (cacheFull.get()) break;

                completionService.submit(() -> {
                    if (cacheFull.get()) return null;

                    int currentProcessed = processed.incrementAndGet();

                    // Check if file exists
                    if (!Files.exists(Path.of(filePath))) {
                        skipped.incrementAndGet();
                        return null;
                    }

                    // Check if extension is supported
                    String ext = getFileExtension(filePath).toLowerCase();
                    if (!supportedExtensions.contains(ext)) {
                        skipped.incrementAndGet();
                        return null;
                    }

                    // Check if already cached
                    if (isThumbnailCached(filePath)) {
                        skipped.incrementAndGet();
                        updateProgress(currentProcessed, totalDocs, filePath, "Skipped",
                                lastProgressUpdate, cached.get(), skipped.get(), failed.get());
                        return null;
                    }

                    // Generate thumbnail
                    try {
                        updateProgress(currentProcessed, totalDocs, filePath, "Caching",
                                lastProgressUpdate, cached.get(), skipped.get(), failed.get());

                        thumbnailService.getThumbnail(filePath);
                        int newCached = cached.incrementAndGet();

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

            // Shutdown executor
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }

            long endTime = System.currentTimeMillis();
            double elapsedSeconds = (endTime - startTime) / 1000.0;

            if (!quiet) {
                System.out.println("\n");
                System.out.println("==========================================");
                System.out.println("Thumbnail Caching Complete");
                System.out.println("==========================================");
                System.out.println("Processed: " + processed.get());
                System.out.println("Cached:    " + cached.get());
                System.out.println("Skipped:   " + skipped.get());
                System.out.println("Failed:    " + failed.get());
                System.out.println("Time:      " + String.format("%.1f", elapsedSeconds) + " seconds");
                if (cached.get() > 0 && elapsedSeconds > 0) {
                    System.out.println("Speed:     " + String.format("%.1f", cached.get() / elapsedSeconds) + " thumbnails/sec");
                }
                System.out.println();

                long finalCacheSize = thumbnailService.getDiskCacheSize();
                int finalCacheCount = thumbnailService.getDiskCacheFileCount();
                System.out.println("Final cache: " + finalCacheCount + " files (" +
                        (finalCacheSize / 1024 / 1024) + " MB)");
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void updateProgress(int current, long total, String filePath, String action,
                                 AtomicLong lastUpdate, int cached, int skipped, int failed) {
        if (quiet) return;

        // Throttle progress updates to avoid console spam
        long now = System.currentTimeMillis();
        if (now - lastUpdate.get() < 100) return; // Update at most every 100ms
        lastUpdate.set(now);

        System.out.print("\r[" + current + "/" + total + "] " + action + ": " +
                truncateFilename(filePath) + " (C:" + cached + " S:" + skipped + " F:" + failed + ")          ");
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--quiet":
                case "-q":
                    quiet = true;
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--parallel":
                case "-p":
                    if (i + 1 < args.length) {
                        try {
                            parallelThreads = Integer.parseInt(args[++i]);
                            if (parallelThreads < 1) parallelThreads = 1;
                            if (parallelThreads > 16) parallelThreads = 16;
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid parallel thread count, using default: 4");
                            parallelThreads = 4;
                        }
                    }
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    System.exit(0);
                    break;
            }
        }
    }

    private void printHelp() {
        System.out.println("PhotoStat CLI - Thumbnail Cache Generator");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --cache-thumbnails [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --parallel N, -p N  Number of parallel threads (1-16, default: 4)");
        System.out.println("  --dry-run           Show what would be cached without actually caching");
        System.out.println("  --quiet, -q         Minimal output");
        System.out.println("  --help, -h          Show this help message");
        System.out.println();
        System.out.println("Description:");
        System.out.println("  Pre-generates and caches thumbnails for all indexed images.");
        System.out.println("  Stops when the configured cache size limit is reached.");
        System.out.println("  This improves GUI performance by having thumbnails ready.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar photostat.jar --cache-thumbnails");
        System.out.println("  java -jar photostat.jar --cache-thumbnails --parallel 8");
        System.out.println("  java -jar photostat.jar --cache-thumbnails -p 2 --quiet");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Cache size can be set in Settings > Cache > Max Cache Size");
    }

    private boolean isThumbnailCached(String filePath) {
        try {
            Path cachePath = getCachePath(filePath);
            return cachePath != null && Files.exists(cachePath);
        } catch (Exception e) {
            return false;
        }
    }

    private Path getCachePath(String filePath) {
        // Replicate the cache key logic from ThumbnailService
        try {
            Path path = Path.of(filePath);
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            long modTime = attrs.lastModifiedTime().toMillis();
            int thumbSize = configService.getThumbnailSize();

            String input = filePath + "|" + modTime + "|" + thumbSize;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String cacheKey = sb.toString();

            return thumbnailService.getDiskCacheDir().resolve(cacheKey + ".jpg");
        } catch (Exception e) {
            return null;
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    private String truncateFilename(String path) {
        String filename = Path.of(path).getFileName().toString();
        if (filename.length() > 40) {
            return filename.substring(0, 37) + "...";
        }
        return filename;
    }
}
