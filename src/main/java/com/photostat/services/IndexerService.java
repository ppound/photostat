package com.photostat.services;

import com.photostat.models.ImageMetadata;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    /**
     * Package-visible constructor for testing with injected dependencies.
     */
    IndexerService(ConfigService configService, ExifService exifService,
                   OpenSearchService openSearchService, SidecarService sidecarService,
                   HashService hashService) {
        this.configService = configService;
        this.exifService = exifService;
        this.openSearchService = openSearchService;
        this.sidecarService = sidecarService;
        this.hashService = hashService;
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

        currentTask = createIndexingTask(directories, false, false);
        Thread thread = new Thread(currentTask);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Re-index all files, ignoring previously indexed status.
     */
    public void reindexAll() {
        reindexAll(false);
    }

    /**
     * Re-index all files, ignoring previously indexed status. When
     * {@code removeOrphans} is {@code true}, an orphan-sweep pass runs after
     * indexing: index entries whose files no longer exist on disk are deleted.
     * Configured directories that are not accessible at sweep time (e.g.
     * unmounted external drives) are skipped so their entries are not removed
     * accidentally.
     */
    public void reindexAll(boolean removeOrphans) {
        if (isIndexing.get()) {
            updateStatus("Indexing already in progress");
            return;
        }

        List<String> directories = configService.getDirectories();
        if (directories.isEmpty()) {
            updateStatus("No directories configured");
            return;
        }

        currentTask = createIndexingTask(directories, true, removeOrphans);
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
    private Task<Void> createIndexingTask(List<String> directories, boolean forceReindex, boolean removeOrphans) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                isIndexing.set(true);
                IndexingStats stats = new IndexingStats();
                int indexingThreads = Runtime.getRuntime().availableProcessors() * 2;

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
                    List<Path> allFiles = collectFiles(directories, extensions);

                    stats.totalFiles.set(allFiles.size());
                    updateStatus("Found " + stats.totalFiles.get() + " image files");

                    if (stats.totalFiles.get() == 0) {
                        notifyProgress(1.0);
                        notifyCompletion(stats);
                        return null;
                    }

                    // Pre-fetch indexed files to avoid N+1 queries during Phase 2
        Set<String> indexedPaths = new HashSet<>();
        if (!forceReindex) {
            updateStatus("Checking previously indexed files...");
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                for (String path : openSearchService.searchAllFilePaths(10000)) {
                    indexedPaths.add(path);
                    if (isWindows) {
                        indexedPaths.add(path.toLowerCase());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error fetching indexed paths: " + e.getMessage());
            }
        }

        // Phase 2: Process files in parallel
        AtomicInteger processedCount = new AtomicInteger(0);
                    AtomicLong lastUIUpdate = new AtomicLong(0);
                    ExecutorService executor = Executors.newFixedThreadPool(indexingThreads);

                    // Drainer thread: workers offer() to the queue lock-free; the drainer
                    // owns all bulkIndex() calls, eliminating synchronized-block contention.
                    LinkedBlockingQueue<ImageMetadata> batchQueue = new LinkedBlockingQueue<>();
                    AtomicBoolean drainerActive = new AtomicBoolean(true);
                    Thread drainerThread = new Thread(() -> {
                        List<ImageMetadata> buffer = new ArrayList<>(batchSize);
                        while (drainerActive.get() || !batchQueue.isEmpty()) {
                            try {
                                ImageMetadata item = batchQueue.poll(10, TimeUnit.MILLISECONDS);
                                if (item != null) {
                                    buffer.add(item);
                                    batchQueue.drainTo(buffer, batchSize - 1);
                                    try {
                                        int indexed = openSearchService.bulkIndex(buffer);
                                        stats.indexedFiles.addAndGet(indexed);
                                    } catch (Exception e) {
                                        System.err.println("Bulk index error: " + e.getMessage());
                                    } finally {
                                        buffer.clear();
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    });
                    drainerThread.setName("indexer-batch-drainer");
                    drainerThread.setDaemon(true);
                    drainerThread.start();

                    updateStatus("Indexing with " + indexingThreads + " threads...");

                    // Use CompletionService so futures are consumed as they complete —
                    // avoids holding all N futures in an ArrayList for large collections.
                    ExecutorCompletionService<Void> completionService =
                            new ExecutorCompletionService<>(executor);
                    int submitted = 0;

                    for (Path file : allFiles) {
                        if (isCancelled()) break;

                        completionService.submit(() -> {
                            if (isCancelled()) return null;

                            try {
                                String filePath = file.toAbsolutePath().toString();

                                // Check if already indexed (unless force reindex)
                                if (!forceReindex) {
                                    boolean isIndexed = indexedPaths.contains(filePath);
                                    if (!isIndexed && System.getProperty("os.name").toLowerCase().contains("win")) {
                                        isIndexed = indexedPaths.contains(filePath.toLowerCase());
                                    }
                                    
                                    if (isIndexed) {
                                        stats.skippedFiles.incrementAndGet();
                                        int current = processedCount.incrementAndGet();
                                        throttledProgressUpdate(lastUIUpdate, current, stats.totalFiles.get());
                                        return null;
                                    }
                                }

                                // Process the file (extract metadata, apply sidecar, compute hashes)
                                ImageMetadata metadata = processFile(file);

                                stats.processedFiles.incrementAndGet();

                                // Hand off to drainer — no lock needed
                                batchQueue.offer(metadata);

                            } catch (Exception e) {
                                stats.errorFiles.incrementAndGet();
                                System.err.println("Error processing " + file + ": " + e.getMessage());
                            }

                            int current = processedCount.incrementAndGet();
                            throttledStatusUpdate(lastUIUpdate, file.getFileName().toString(),
                                    current, stats.totalFiles.get());
                            return null;
                        });
                        submitted++;
                    }

                    // Consume completions as they arrive — O(1) memory per task.
                    // Workers catch all exceptions internally, so we only need to
                    // handle InterruptedException from take().
                    for (int i = 0; i < submitted && !isCancelled(); i++) {
                        try {
                            completionService.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    // Shutdown worker executor
                    executor.shutdown();
                    try {
                        executor.awaitTermination(1, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                    }

                    // All workers done — signal drainer to finish and wait for final flush
                    drainerActive.set(false);
                    try {
                        drainerThread.join(60_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    if (isCancelled()) {
                        updateStatus("Indexing cancelled. Indexed " + stats.indexedFiles.get() + " files.");
                    } else {
                        updateStatus("Indexing complete. Indexed " + stats.indexedFiles.get() + " files.");
                    }

                    // Phase 3 (opt-in): remove orphaned index entries for files that
                    // are gone from disk. Only sweeps within configured directories
                    // that are currently accessible — entries under an unmounted
                    // external drive are left alone.
                    if (removeOrphans && !isCancelled()) {
                        try {
                            sweepOrphans(directories, allFiles, stats);
                        } catch (Exception e) {
                            updateStatus("Orphan sweep failed: " + e.getMessage());
                            System.err.println("Orphan sweep error: " + e.getMessage());
                        }
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
            updateStatus("Processing: " + fileName + " (" + current + "/" + total + ")");
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
     * Remove OpenSearch documents for files that no longer exist on disk
     * under the supplied directories. Skips any configured directory that
     * isn't currently accessible (e.g. an unmounted external drive) so its
     * entries are not deleted accidentally.
     */
    private void sweepOrphans(List<String> directories, List<Path> diskFiles, IndexingStats stats) throws IOException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Directories we'll actually sweep — those that exist now.
        List<String> sweepable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String dir : directories) {
            if (Files.isDirectory(Path.of(dir))) {
                sweepable.add(normalizeForCompare(dir, isWindows));
            } else {
                skipped.add(dir);
            }
        }

        if (sweepable.isEmpty()) {
            updateStatus("Orphan sweep skipped: no configured directories are accessible.");
            return;
        }
        if (!skipped.isEmpty()) {
            updateStatus("Orphan sweep: skipping " + skipped.size() +
                    " inaccessible director(y/ies); entries there will not be removed.");
        }

        // Set of paths we just saw on disk (normalized for case-insensitive
        // comparison on Windows).
        Set<String> diskPaths = new HashSet<>(diskFiles.size());
        for (Path p : diskFiles) {
            diskPaths.add(normalizeForCompare(p.toAbsolutePath().toString(), isWindows));
        }

        updateStatus("Checking for orphaned index entries...");

        int orphans = 0;
        for (String indexedPath : openSearchService.searchAllFilePaths(10000)) {
            if (currentTask != null && currentTask.isCancelled()) {
                break;
            }
            String normalized = normalizeForCompare(indexedPath, isWindows);
            if (!isUnderAny(normalized, sweepable)) {
                continue;
            }
            if (!diskPaths.contains(normalized)) {
                try {
                    if (openSearchService.deleteDocument(indexedPath)) {
                        orphans++;
                    }
                } catch (Exception e) {
                    System.err.println("Failed to delete orphan " + indexedPath + ": " + e.getMessage());
                }
            }
        }

        stats.orphansDeleted.set(orphans);
        updateStatus("Orphan sweep removed " + orphans + " stale index entr" + (orphans == 1 ? "y" : "ies") + ".");
    }

    private static String normalizeForCompare(String path, boolean isWindows) {
        String norm;
        try {
            norm = Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            norm = path;
        }
        return isWindows ? norm.toLowerCase() : norm;
    }

    private static boolean isUnderAny(String normalizedPath, List<String> normalizedDirs) {
        for (String d : normalizedDirs) {
            if (normalizedPath.equals(d)) return true;
            if (!normalizedPath.startsWith(d)) continue;
            char next = normalizedPath.charAt(d.length());
            if (next == '/' || next == '\\' || next == java.io.File.separatorChar) {
                return true;
            }
        }
        return false;
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

    /**
     * Get the file extension including the dot (e.g. ".jpg").
     */
    String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    /**
     * Collect all image files from the given directories that match the specified extensions.
     * Walks the directory tree recursively.
     *
     * @param directories list of directory paths to scan
     * @param extensions set of lowercase file extensions to match (e.g. ".jpg", ".png")
     * @return list of matching file paths
     */
    List<Path> collectFiles(List<String> directories, Set<String> extensions) throws IOException {
        List<Path> allFiles = new ArrayList<>();

        for (String dirPath : directories) {
            Path dir = Paths.get(dirPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                continue;
            }

            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
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

        return allFiles;
    }

    /**
     * Process a single image file: extract metadata, apply sidecar data, and compute hashes.
     *
     * @param file the image file to process
     * @return the extracted and enriched metadata
     */
    ImageMetadata processFile(Path file) throws Exception {
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

        return metadata;
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
        public final AtomicLong orphansDeleted = new AtomicLong(0);

        @Override
        public String toString() {
            String base = String.format(
                    "Total: %d, Processed: %d, Indexed: %d, Skipped: %d, Errors: %d",
                    totalFiles.get(), processedFiles.get(), indexedFiles.get(),
                    skippedFiles.get(), errorFiles.get()
            );
            long orphans = orphansDeleted.get();
            return orphans > 0 ? base + ", Orphans removed: " + orphans : base;
        }
    }
}
