package com.photostat.cli;

import com.photostat.models.ImageMetadata;
import com.photostat.services.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-line interface for batch image analysis.
 * Runs without GUI, using existing configuration from config.json.
 */
public class AnalyzeCli {

    private final ConfigService configService;
    private final ImageAnalysisService imageAnalysisService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;
    private final ExifService exifService;

    // Supported formats for AI analysis (same as GUI)
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    // All image formats for finding files
    private static final Set<String> ALL_IMAGE_FORMATS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".tiff", ".tif", ".bmp",
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf"
    );

    private boolean dryRun = false;
    private boolean force = false;
    private boolean quiet = false;
    private boolean showProgress = true;
    private String specificDir = null;
    private String providerOverride = null;
    private int parallelThreads = 1;

    // Retry settings for rate limit errors
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;

    public AnalyzeCli() {
        this.configService = ConfigService.getInstance();
        this.imageAnalysisService = ImageAnalysisService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.exifService = ExifService.getInstance();
    }

    /**
     * Parse command-line arguments and run analysis.
     */
    public int run(String[] args) {
        // Parse arguments
        if (!parseArgs(args)) {
            return 1;
        }

        // Show configuration
        if (!quiet) {
            printConfig();
        }

        // Check if API is configured
        if (!imageAnalysisService.isConfigured()) {
            System.err.println("Error: No API key configured for " + imageAnalysisService.getProviderName());
            System.err.println("Please configure your API key in ~/.photostat/config.json or via the GUI Settings.");
            return 1;
        }

        // Connect to OpenSearch
        if (!connectToOpenSearch()) {
            System.err.println("Error: Failed to connect to OpenSearch. Check your configuration.");
            return 1;
        }

        // Get directories to analyze
        List<String> directories = getDirectories();
        if (directories.isEmpty()) {
            System.err.println("Error: No directories configured for indexing.");
            System.err.println("Add directories via the GUI or edit ~/.photostat/config.json");
            return 1;
        }

        // Find all image files
        if (!quiet) {
            System.out.println("\nScanning directories for images...");
        }
        List<File> imageFiles = findImageFiles(directories);

        if (imageFiles.isEmpty()) {
            System.out.println("No supported images found for analysis.");
            return 0;
        }

        // Filter to supported formats for analysis
        List<File> analyzableFiles = imageFiles.stream()
                .filter(f -> isSupportedForAnalysis(f.getName()))
                .toList();

        if (!quiet) {
            System.out.println("Found " + imageFiles.size() + " images, " + analyzableFiles.size() + " supported for analysis.");
        }

        if (analyzableFiles.isEmpty()) {
            System.out.println("No images in supported formats (JPG, PNG, GIF, WebP) found.");
            return 0;
        }

        // Check cache and filter
        List<File> filesToAnalyze;
        if (force) {
            filesToAnalyze = analyzableFiles;
            if (!quiet) {
                System.out.println("Force mode: will re-analyze all " + filesToAnalyze.size() + " images.");
            }
        } else {
            filesToAnalyze = new ArrayList<>();
            int cachedCount = 0;
            for (File file : analyzableFiles) {
                if (imageAnalysisService.isAnalysisCached(file.getAbsolutePath())) {
                    cachedCount++;
                } else {
                    filesToAnalyze.add(file);
                }
            }
            if (!quiet) {
                System.out.println("Skipping " + cachedCount + " cached images, " + filesToAnalyze.size() + " to analyze.");
            }
        }

        if (filesToAnalyze.isEmpty()) {
            System.out.println("All images are already analyzed (cached). Use --force to re-analyze.");
            return 0;
        }

        // Dry run - just show what would be done
        if (dryRun) {
            System.out.println("\n[DRY RUN] Would analyze " + filesToAnalyze.size() + " images:");
            int count = 0;
            for (File file : filesToAnalyze) {
                System.out.println("  " + file.getAbsolutePath());
                count++;
                if (count >= 20 && filesToAnalyze.size() > 25) {
                    System.out.println("  ... and " + (filesToAnalyze.size() - 20) + " more");
                    break;
                }
            }
            return 0;
        }

        // Run analysis
        return runAnalysis(filesToAnalyze);
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--analyze":
                    // Main command flag - already handled by Launcher
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
                case "--provider":
                    if (i + 1 < args.length) {
                        providerOverride = args[++i].toLowerCase();
                        if (!providerOverride.equals("claude") && !providerOverride.equals("gemini")) {
                            System.err.println("Error: --provider must be 'claude' or 'gemini'");
                            return false;
                        }
                        // Override the provider in config
                        configService.setAiProvider(providerOverride);
                    } else {
                        System.err.println("Error: --provider requires 'claude' or 'gemini'");
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
                case "--parallel":
                    if (i + 1 < args.length) {
                        try {
                            parallelThreads = Integer.parseInt(args[++i]);
                            if (parallelThreads < 1 || parallelThreads > 8) {
                                System.err.println("Error: --parallel must be between 1 and 8");
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Error: --parallel requires a number");
                            return false;
                        }
                    } else {
                        System.err.println("Error: --parallel requires a number (1-8)");
                        printUsage();
                        return false;
                    }
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
        System.out.println("PhotoStat CLI - Batch Image Analysis");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --analyze [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dir <path>           Analyze specific directory (overrides config)");
        System.out.println("  --provider <name>      Use 'claude' or 'gemini' (overrides config)");
        System.out.println("  --parallel <n>         Run n parallel analyses (1-8, default: 1)");
        System.out.println("  --dry-run              Show what would be analyzed without calling API");
        System.out.println("  --force                Re-analyze even if cached");
        System.out.println("  --quiet, -q            Minimal output");
        System.out.println("  --no-progress          Disable progress updates");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Configuration: ~/.photostat/config.json");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar photostat.jar --analyze");
        System.out.println("  java -jar photostat.jar --analyze --provider gemini");
        System.out.println("  java -jar photostat.jar --analyze --dir /path/to/photos --force");
        System.out.println("  java -jar photostat.jar --analyze --dry-run");
    }

    private void printConfig() {
        System.out.println("PhotoStat CLI - Batch Image Analysis");
        System.out.println("=====================================");
        System.out.println("AI Provider: " + imageAnalysisService.getProviderName());
        System.out.println("Parallel:    " + parallelThreads + " thread" + (parallelThreads > 1 ? "s" : ""));
        System.out.println("Config: " + configService.getConfigPath());
    }

    private boolean connectToOpenSearch() {
        try {
            openSearchService.connect();
            return openSearchService.testConnection();
        } catch (Exception e) {
            System.err.println("OpenSearch connection error: " + e.getMessage());
            return false;
        }
    }

    private List<String> getDirectories() {
        if (specificDir != null) {
            File dir = new File(specificDir);
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("Error: Directory does not exist: " + specificDir);
                return List.of();
            }
            return List.of(specificDir);
        }
        return configService.getDirectories();
    }

    private List<File> findImageFiles(List<String> directories) {
        List<File> files = new ArrayList<>();

        for (String dirPath : directories) {
            Path dir = Paths.get(dirPath);
            if (!Files.exists(dir)) {
                if (!quiet) {
                    System.out.println("Warning: Directory not found: " + dirPath);
                }
                continue;
            }

            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString().toLowerCase();
                        for (String ext : ALL_IMAGE_FORMATS) {
                            if (name.endsWith(ext)) {
                                files.add(file.toFile());
                                break;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // Skip files we can't access
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                if (!quiet) {
                    System.err.println("Error scanning directory " + dirPath + ": " + e.getMessage());
                }
            }
        }

        return files;
    }

    private boolean isSupportedForAnalysis(String filename) {
        String lower = filename.toLowerCase();
        for (String ext : SUPPORTED_FORMATS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private int runAnalysis(List<File> files) {
        int total = files.size();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);

        // Token usage tracking for cost estimation (thread-safe)
        AtomicLong totalInputTokens = new AtomicLong(0);
        AtomicLong totalOutputTokens = new AtomicLong(0);

        String provider = configService.getAiProvider();
        String model = "gemini".equalsIgnoreCase(provider) ?
                configService.getGeminiModel() : configService.getClaudeModel();

        if (!quiet) {
            System.out.println("\nStarting analysis of " + total + " images using " +
                    imageAnalysisService.getProviderName() + " (" + model + ")" +
                    (parallelThreads > 1 ? " with " + parallelThreads + " parallel threads" : "") + "...\n");
        }

        long startTime = System.currentTimeMillis();

        if (parallelThreads > 1) {
            // Parallel processing
            runParallelAnalysis(files, total, success, failed, current, totalInputTokens, totalOutputTokens);
        } else {
            // Sequential processing (original behavior)
            runSequentialAnalysis(files, total, success, failed, current, totalInputTokens, totalOutputTokens);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedMinutes = elapsed / 60000.0;

        System.out.println();
        System.out.println("=====================================");
        System.out.println("Analysis Complete");
        System.out.println("=====================================");
        System.out.println("Total:     " + total);
        System.out.println("Success:   " + success.get());
        System.out.println("Failed:    " + failed.get());
        System.out.printf("Time:      %.1f minutes%n", elapsedMinutes);
        if (success.get() > 0) {
            System.out.printf("Avg:       %.1f seconds/image%n", (elapsed / 1000.0) / success.get());
        }

        // Display token usage and cost estimate (for Gemini)
        if (totalInputTokens.get() > 0 || totalOutputTokens.get() > 0) {
            System.out.println();
            System.out.println("Token Usage:");
            System.out.printf("  Input:   %,d tokens%n", totalInputTokens.get());
            System.out.printf("  Output:  %,d tokens%n", totalOutputTokens.get());
            System.out.printf("  Total:   %,d tokens%n", totalInputTokens.get() + totalOutputTokens.get());

            // Estimate cost based on model
            double estimatedCost = estimateCost(model, totalInputTokens.get(), totalOutputTokens.get());
            if (estimatedCost > 0) {
                System.out.printf("Est. Cost: $%.4f%n", estimatedCost);
            }
        }

        return failed.get() > 0 ? 1 : 0;
    }

    /**
     * Run sequential (single-threaded) analysis.
     */
    private void runSequentialAnalysis(List<File> files, int total,
                                        AtomicInteger success, AtomicInteger failed, AtomicInteger current,
                                        AtomicLong totalInputTokens, AtomicLong totalOutputTokens) {
        for (File file : files) {
            current.incrementAndGet();
            processFile(file, total, success, failed, current, totalInputTokens, totalOutputTokens, null);
        }
    }

    /**
     * Run parallel (multi-threaded) analysis using ExecutorCompletionService.
     * A single drainer thread serializes all OpenSearch writes, eliminating contention.
     */
    private void runParallelAnalysis(List<File> files, int total,
                                      AtomicInteger success, AtomicInteger failed, AtomicInteger current,
                                      AtomicLong totalInputTokens, AtomicLong totalOutputTokens) {
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        LinkedBlockingQueue<ImageMetadata> updateQueue = new LinkedBlockingQueue<>();

        // Drainer: single thread writes all queued metadata to OpenSearch sequentially
        AtomicBoolean drainerActive = new AtomicBoolean(true);
        Thread drainer = new Thread(() -> {
            while (drainerActive.get() || !updateQueue.isEmpty()) {
                try {
                    ImageMetadata m = updateQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (m != null) {
                        try {
                            openSearchService.updateDocument(m);
                        } catch (Exception e) {
                            System.err.println("OpenSearch update failed: " + e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        drainer.setName("analyze-os-drainer");
        drainer.setDaemon(true);
        drainer.start();

        int submitted = 0;
        for (File file : files) {
            completionService.submit(() -> {
                current.incrementAndGet();
                processFile(file, total, success, failed, current, totalInputTokens, totalOutputTokens, updateQueue);
                return null;
            });
            submitted++;
        }

        // Wait for all worker tasks to complete
        for (int i = 0; i < submitted; i++) {
            try {
                completionService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        executor.shutdown();

        // Signal drainer to finish remaining items and exit
        drainerActive.set(false);
        try {
            drainer.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Process a single file with retry logic for rate limit errors.
     * If updateQueue is non-null, enqueues the metadata for the drainer thread to write;
     * otherwise writes directly to OpenSearch (sequential mode).
     */
    private void processFile(File file, int total,
                              AtomicInteger success, AtomicInteger failed, AtomicInteger current,
                              AtomicLong totalInputTokens, AtomicLong totalOutputTokens,
                              LinkedBlockingQueue<ImageMetadata> updateQueue) {
        String filePath = file.getAbsolutePath();

        if (showProgress) {
            synchronized (System.out) {
                String progress = String.format("[%d/%d] ", current.get(), total);
                System.out.print(progress + "Analyzing: " + file.getName() + "... ");
                System.out.flush();
            }
        }

        try {
            // Run analysis with retry logic
            ImageAnalysisService.AnalysisResult result = analyzeWithRetry(filePath);

            // Accumulate token usage
            totalInputTokens.addAndGet(result.getInputTokens());
            totalOutputTokens.addAndGet(result.getOutputTokens());

            if (result.hasError()) {
                failed.incrementAndGet();
                if (showProgress) {
                    synchronized (System.out) {
                        System.out.println("FAILED: " + result.getError());
                    }
                }
                return;
            }

            // Get or create metadata
            ImageMetadata metadata = getOrCreateMetadata(filePath);

            // Merge analysis results with existing metadata (don't overwrite user data)
            if (result.getTags() != null && !result.getTags().isEmpty()) {
                for (String tag : result.getTags()) {
                    metadata.addTag(tag);
                }
            }
            if (result.getPersons() != null && !result.getPersons().isEmpty()) {
                for (String person : result.getPersons()) {
                    metadata.addPerson(person);
                }
            }
            if (result.getPlace() != null && !result.getPlace().isEmpty()) {
                if (metadata.getPlace() == null || metadata.getPlace().isEmpty()) {
                    metadata.setPlace(result.getPlace());
                }
            }
            if (result.getRating() != null && !result.getRating().isEmpty()) {
                if (metadata.getRating() == null || metadata.getRating().isEmpty()) {
                    metadata.setRating(result.getRating());
                }
            }

            // Write to OpenSearch: queue for drainer (parallel) or direct (sequential)
            if (updateQueue != null) {
                updateQueue.offer(metadata);
            } else {
                openSearchService.updateDocument(metadata);
            }

            // Save to sidecar
            sidecarService.writeSidecar(metadata);

            success.incrementAndGet();

            if (showProgress) {
                synchronized (System.out) {
                    System.out.println("OK (tags: " + result.getTags().size() +
                            ", rating: " + result.getRating() + ")");
                }
            }

        } catch (Exception e) {
            failed.incrementAndGet();
            if (showProgress) {
                synchronized (System.out) {
                    System.out.println("ERROR: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Analyze an image with retry logic for rate limit (429) errors.
     */
    private ImageAnalysisService.AnalysisResult analyzeWithRetry(String filePath) {
        ImageAnalysisService.AnalysisResult result = null;
        long delayMs = INITIAL_RETRY_DELAY_MS;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            result = imageAnalysisService.analyzeImage(filePath);

            // Check if it's a rate limit error (429)
            if (result.hasError() && result.getError() != null &&
                    (result.getError().contains("429") || result.getError().toLowerCase().contains("rate limit"))) {

                if (attempt < MAX_RETRIES) {
                    // Wait with exponential backoff
                    try {
                        Thread.sleep(delayMs);
                        delayMs *= 2;  // Exponential backoff
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return result;
                    }
                    continue;
                }
            }

            // Success or non-retryable error
            break;
        }

        return result;
    }

    /**
     * Estimate cost based on model and token usage.
     * Prices are approximate and may change.
     */
    private double estimateCost(String model, long inputTokens, long outputTokens) {
        // Gemini pricing (per 1M tokens) - approximate as of early 2025
        double inputPricePer1M;
        double outputPricePer1M;

        String modelLower = model.toLowerCase();
        if (modelLower.contains("gemini-2.0-flash") || modelLower.contains("gemini-2.0-flash-exp")) {
            // Gemini 2.0 Flash - very affordable
            inputPricePer1M = 0.10;
            outputPricePer1M = 0.40;
        } else if (modelLower.contains("gemini-1.5-flash")) {
            // Gemini 1.5 Flash
            inputPricePer1M = 0.075;
            outputPricePer1M = 0.30;
        } else if (modelLower.contains("gemini-1.5-pro")) {
            // Gemini 1.5 Pro
            inputPricePer1M = 1.25;
            outputPricePer1M = 5.00;
        } else if (modelLower.contains("claude")) {
            // Claude models - we don't have token counts for Claude currently
            return 0;
        } else {
            // Unknown model - use conservative estimate
            inputPricePer1M = 0.10;
            outputPricePer1M = 0.40;
        }

        double inputCost = (inputTokens / 1_000_000.0) * inputPricePer1M;
        double outputCost = (outputTokens / 1_000_000.0) * outputPricePer1M;

        return inputCost + outputCost;
    }

    private ImageMetadata getOrCreateMetadata(String filePath) {
        // Try to get existing metadata from OpenSearch
        try {
            ImageMetadata existing = openSearchService.getDocumentByPath(filePath);
            if (existing != null) {
                return existing;
            }
        } catch (Exception e) {
            // Ignore - will create new
        }

        // Create new metadata from EXIF
        try {
            return exifService.extractMetadata(Paths.get(filePath));
        } catch (Exception e) {
            // Create minimal metadata
            ImageMetadata metadata = new ImageMetadata();
            metadata.setFilePath(filePath);
            File file = new File(filePath);
            metadata.setFileName(file.getName());
            return metadata;
        }
    }
}
