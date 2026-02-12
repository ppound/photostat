package com.photostat.services;

import com.photostat.models.ImageMetadata;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Service for indexing image files in the background.
 */
public class IndexerService {

    private static IndexerService instance;
    private final ConfigService configService;
    private final ExifService exifService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;
    private final HashService hashService;

    private Task<Void> currentTask;
    private final AtomicBoolean isIndexing = new AtomicBoolean(false);

    // Progress callbacks
    private Consumer<String> statusCallback;
    private Consumer<Double> progressCallback;
    private Consumer<IndexingStats> completionCallback;

    private IndexerService() {
        this.configService = ConfigService.getInstance();
        this.exifService = ExifService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.hashService = HashService.getInstance();
    }

    public static synchronized IndexerService getInstance() {
        if (instance == null) {
            instance = new IndexerService();
        }
        return instance;
    }

    /**
     * Set callbacks for progress reporting.
     */
    public void setCallbacks(Consumer<String> statusCallback,
                            Consumer<Double> progressCallback,
                            Consumer<IndexingStats> completionCallback) {
        this.statusCallback = statusCallback;
        this.progressCallback = progressCallback;
        this.completionCallback = completionCallback;
    }

    /**
     * Start indexing configured directories.
     */
    public void startIndexing() {
        if (isIndexing.get()) {
            updateStatus("Indexing already in progress");
            return;
        }

        List<String> directories = configService.getDirectories();
        if (directories.isEmpty()) {
            updateStatus("No directories configured");
            return;
        }

        startIndexing(directories);
    }

    /**
     * Start indexing specific directories.
     */
    public void startIndexing(List<String> directories) {
        if (isIndexing.get()) {
            updateStatus("Indexing already in progress");
            return;
        }

        currentTask = createIndexingTask(directories, false);
        Thread thread = new Thread(currentTask);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Re-index all files, ignoring previously indexed status.
     */
    public void reindexAll() {
        if (isIndexing.get()) {
            updateStatus("Indexing already in progress");
            return;
        }

        List<String> directories = configService.getDirectories();
        if (directories.isEmpty()) {
            updateStatus("No directories configured");
            return;
        }

        currentTask = createIndexingTask(directories, true);
        Thread thread = new Thread(currentTask);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stop the current indexing task.
     */
    public void stopIndexing() {
        if (currentTask != null && isIndexing.get()) {
            currentTask.cancel();
            updateStatus("Indexing cancelled");
        }
    }

    /**
     * Check if indexing is in progress.
     */
    public boolean isIndexing() {
        return isIndexing.get();
    }

    /**
     * Create the indexing task.
     */
    private Task<Void> createIndexingTask(List<String> directories, boolean forceReindex) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                isIndexing.set(true);
                IndexingStats stats = new IndexingStats();
                int indexingThreads = configService.getIndexingThreads();

                try {
                    // Ensure OpenSearch is connected and index exists
                    if (!openSearchService.isConnected()) {
                        openSearchService.connect();
                    }
                    openSearchService.createIndexIfNotExists();

                    // Get file extensions to process
                    Set<String> extensions = configService.getFileExtensions().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());

                    int batchSize = configService.getBatchSize();

                    // Phase 1: Collect all file paths (fast, sequential)
                    updateStatus("Scanning directories...");
                    List<Path> allFiles = new ArrayList<>();

                    for (String dirPath : directories) {
                        if (isCancelled()) break;

                        Path dir = Paths.get(dirPath);
                        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                            continue;
                        }

                        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (isCancelled()) return FileVisitResult.TERMINATE;

                                String ext = getFileExtension(file.getFileName().toString()).toLowerCase();
                                if (extensions.contains(ext)) {
                                    allFiles.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }

                    stats.totalFiles.set(allFiles.size());
                    updateStatus("Found " + stats.totalFiles.get() + " image files");

                    if (stats.totalFiles.get() == 0) {
                        notifyProgress(1.0);
                        notifyCompletion(stats);
                        return null;
                    }

                    // Phase 2: Process files in parallel
                    AtomicInteger processedCount = new AtomicInteger(0);
                    List<ImageMetadata> batch = Collections.synchronizedList(new ArrayList<>());
                    AtomicLong lastUIUpdate = new AtomicLong(0);
                    ExecutorService executor = Executors.newFixedThreadPool(indexingThreads);

                    updateStatus("Indexing with " + indexingThreads + " threads...");

                    List<Future<?>> futures = new ArrayList<>();

                    for (Path file : allFiles) {
                        if (isCancelled()) break;

                        futures.add(executor.submit(() -> {
                            if (isCancelled()) return;

                            try {
                                String filePath = file.toAbsolutePath().toString();

                                // Check if already indexed (unless force reindex)
                                if (!forceReindex && openSearchService.isFileIndexed(filePath)) {
                                    stats.skippedFiles.incrementAndGet();
                                    int current = processedCount.incrementAndGet();
                                    throttledProgressUpdate(lastUIUpdate, current, stats.totalFiles.get());
                                    return;
                                }

                                // Extract metadata
                                ImageMetadata metadata = exifService.extractMetadata(file);

                                // Apply sidecar data if exists (preserves custom metadata on reindex)
                                sidecarService.applySidecarToMetadata(metadata);

                                // Compute content and perceptual hashes
                                try {
                                    metadata.setContentHash(hashService.computeContentHash(file));
                                    metadata.setPerceptualHash(hashService.computePerceptualHash(file));
                                } catch (Exception e) {
                                    // Non-fatal: continue indexing even if hashing fails
                                    System.err.println("Hash computation failed for " + file + ": " + e.getMessage());
                                }

                                stats.processedFiles.incrementAndGet();

                                // Add to batch and flush when full
                                List<ImageMetadata> toFlush = null;
                                synchronized (batch) {
                                    batch.add(metadata);
                                    if (batch.size() >= batchSize) {
                                        toFlush = new ArrayList<>(batch);
                                        batch.clear();
                                    }
                                }

                                if (toFlush != null) {
                                    int indexed = openSearchService.bulkIndex(toFlush);
                                    stats.indexedFiles.addAndGet(indexed);
                                }

                            } catch (Exception e) {
                                stats.errorFiles.incrementAndGet();
                                System.err.println("Error processing " + file + ": " + e.getMessage());
                            }

                            int current = processedCount.incrementAndGet();
                            throttledStatusUpdate(lastUIUpdate, file.getFileName().toString(),
                                    current, stats.totalFiles.get());
                        }));
                    }

                    // Wait for all tasks to complete
                    for (Future<?> future : futures) {
                        if (isCancelled()) break;
                        try {
                            future.get();
                        } catch (Exception e) {
                            // Task failed, already counted in errorFiles
                        }
                    }

                    // Shutdown executor
                    executor.shutdown();
                    try {
                        executor.awaitTermination(1, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                    }

                    // Flush remaining batch
                    synchronized (batch) {
                        if (!batch.isEmpty() && !isCancelled()) {
                            int indexed = openSearchService.bulkIndex(new ArrayList<>(batch));
                            stats.indexedFiles.addAndGet(indexed);
                            batch.clear();
                        }
                    }

                    if (isCancelled()) {
                        updateStatus("Indexing cancelled. Indexed " + stats.indexedFiles.get() + " files.");
                    } else {
                        updateStatus("Indexing complete. Indexed " + stats.indexedFiles.get() + " files.");
                    }

                    notifyCompletion(stats);

                } catch (Exception e) {
                    updateStatus("Indexing error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    isIndexing.set(false);
                }

                return null;
            }
        };
    }

    private void throttledStatusUpdate(AtomicLong lastUpdate, String fileName, int current, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate.get() >= 100) { // Update at most every 100ms
            lastUpdate.set(now);
            updateStatusSafe("Processing: " + fileName + " (" + current + "/" + total + ")");
            updateProgressSafe(current, total);
        }
    }

    private void throttledProgressUpdate(AtomicLong lastUpdate, int current, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate.get() >= 100) {
            lastUpdate.set(now);
            updateProgressSafe(current, total);
        }
    }

    /**
     * Delete all documents for a specific directory.
     */
    public long deleteDirectory(String directoryPath) {
        try {
            if (!openSearchService.isConnected()) {
                openSearchService.connect();
            }
            return openSearchService.deleteByDirectory(directoryPath);
        } catch (Exception e) {
            System.err.println("Error deleting directory " + directoryPath + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Index a single file at the given path.
     * Useful for re-indexing files that have been moved.
     *
     * @param filePath The path to the file to index
     * @return true if indexing succeeded, false otherwise
     */
    public boolean indexSingleFile(String filePath) {
        try {
            if (!openSearchService.isConnected()) {
                openSearchService.connect();
            }

            Path file = Path.of(filePath);
            if (!Files.exists(file)) {
                System.err.println("File does not exist: " + filePath);
                return false;
            }

            // Extract metadata
            ImageMetadata metadata = exifService.extractMetadata(file);

            // Apply sidecar data if exists
            sidecarService.applySidecarToMetadata(metadata);

            // Compute content and perceptual hashes
            try {
                metadata.setContentHash(hashService.computeContentHash(file));
                metadata.setPerceptualHash(hashService.computePerceptualHash(file));
            } catch (Exception e) {
                System.err.println("Hash computation failed for " + filePath + ": " + e.getMessage());
            }

            // Index the document
            openSearchService.indexDocument(metadata);

            return true;
        } catch (Exception e) {
            System.err.println("Error indexing file " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    private void updateStatus(String message) {
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(message));
        }
    }

    private void updateStatusSafe(String message) {
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(message));
        }
    }

    private void notifyProgress(double progress) {
        if (progressCallback != null) {
            Platform.runLater(() -> progressCallback.accept(progress));
        }
    }

    private void updateProgressSafe(long current, long total) {
        if (progressCallback != null && total > 0) {
            double progress = (double) current / total;
            Platform.runLater(() -> progressCallback.accept(progress));
        }
    }

    private void notifyCompletion(IndexingStats stats) {
        if (completionCallback != null) {
            Platform.runLater(() -> completionCallback.accept(stats));
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    /**
     * Statistics about an indexing operation.
     */
    public static class IndexingStats {
        public final AtomicLong totalFiles = new AtomicLong(0);
        public final AtomicLong processedFiles = new AtomicLong(0);
        public final AtomicLong indexedFiles = new AtomicLong(0);
        public final AtomicLong skippedFiles = new AtomicLong(0);
        public final AtomicLong errorFiles = new AtomicLong(0);

        @Override
        public String toString() {
            return String.format(
                    "Total: %d, Processed: %d, Indexed: %d, Skipped: %d, Errors: %d",
                    totalFiles.get(), processedFiles.get(), indexedFiles.get(),
                    skippedFiles.get(), errorFiles.get()
            );
        }
    }
}
