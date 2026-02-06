package com.photostat.cli;

import com.photostat.models.ImageMetadata;
import com.photostat.services.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

        if (!quiet) {
            System.out.println("\nStarting analysis of " + total + " images using " +
                    imageAnalysisService.getProviderName() + "...\n");
        }

        long startTime = System.currentTimeMillis();

        for (File file : files) {
            current.incrementAndGet();
            String filePath = file.getAbsolutePath();

            if (showProgress) {
                String progress = String.format("[%d/%d] ", current.get(), total);
                System.out.print(progress + "Analyzing: " + file.getName() + "... ");
                System.out.flush();
            }

            try {
                // Run analysis
                ImageAnalysisService.AnalysisResult result = imageAnalysisService.analyzeImage(filePath);

                if (result.hasError()) {
                    failed.incrementAndGet();
                    if (showProgress) {
                        System.out.println("FAILED: " + result.getError());
                    }
                    continue;
                }

                // Get or create metadata
                ImageMetadata metadata = getOrCreateMetadata(filePath);

                // Update metadata with analysis results
                if (result.getTags() != null && !result.getTags().isEmpty()) {
                    metadata.setTags(result.getTags());
                }
                if (result.getPersons() != null && !result.getPersons().isEmpty()) {
                    metadata.setPersons(result.getPersons());
                }
                if (result.getPlace() != null && !result.getPlace().isEmpty()) {
                    metadata.setPlace(result.getPlace());
                }
                if (result.getRating() != null && !result.getRating().isEmpty()) {
                    metadata.setRating(result.getRating());
                }

                // Save to OpenSearch
                openSearchService.updateDocument(metadata);

                // Save to sidecar
                sidecarService.writeSidecar(metadata);

                success.incrementAndGet();

                if (showProgress) {
                    System.out.println("OK (tags: " + result.getTags().size() +
                            ", rating: " + result.getRating() + ")");
                }

            } catch (Exception e) {
                failed.incrementAndGet();
                if (showProgress) {
                    System.out.println("ERROR: " + e.getMessage());
                }
            }
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

        return failed.get() > 0 ? 1 : 0;
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
