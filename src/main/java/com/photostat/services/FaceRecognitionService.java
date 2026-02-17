package com.photostat.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photostat.models.FaceCluster;
import com.photostat.models.FaceDetection;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Service for face detection and recognition using a Python/InsightFace sidecar process.
 * Supports incremental scanning, chunked batches, and parallel Python workers.
 */
public class FaceRecognitionService {

    private static FaceRecognitionService instance;
    private final ConfigService configService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper;

    private final Path facesDir;
    private final Path faceDataPath;
    private final Path clustersPath;
    private final Path scriptPath;

    private static final int CHUNK_SIZE = 500;

    private List<FaceDetection> faceDetections = new ArrayList<>();
    private List<FaceCluster> clusters = new ArrayList<>();
    private Map<String, FaceDetection> faceIndex = new HashMap<>();
    /** Set of image paths that have already been scanned for faces. */
    private Set<String> scannedPaths = new HashSet<>();

    private FaceRecognitionService() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();

        String userHome = System.getProperty("user.home");
        this.facesDir = Path.of(userHome, ".photostat", "faces");
        this.faceDataPath = facesDir.resolve("face_data.json");
        this.clustersPath = facesDir.resolve("clusters.json");
        this.scriptPath = Path.of(userHome, ".photostat", "photostat_faces.py");

        try {
            Files.createDirectories(facesDir);
            Files.createDirectories(facesDir.resolve("crops"));
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to create faces directory", e);
        }

        extractScript();
        loadState();
    }

    public static synchronized FaceRecognitionService getInstance() {
        if (instance == null) {
            instance = new FaceRecognitionService();
        }
        return instance;
    }

    /**
     * Extract the Python script from JAR resources to ~/.photostat/.
     */
    private void extractScript() {
        try (InputStream is = getClass().getResourceAsStream("/photostat_faces.py")) {
            if (is == null) {
                logger.error("FaceRecognitionService", "Python script not found in resources");
                return;
            }
            Files.copy(is, scriptPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("FaceRecognitionService", "Extracted Python script to: " + scriptPath);
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to extract Python script", e);
        }
    }

    /**
     * Check if Python and InsightFace are available.
     */
    public boolean isPythonAvailable() {
        try {
            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString(), "check");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.contains("\"status\": \"ok\"")) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("FaceRecognitionService", "Python check failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Get version info from the Python environment.
     */
    public String getPythonVersionInfo() {
        try {
            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString(), "check");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();
            return output;
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Filter out already-scanned image paths, returning only new ones.
     */
    public List<String> filterNewPaths(List<String> imagePaths) {
        return imagePaths.stream()
                .filter(p -> !scannedPaths.contains(p))
                .collect(Collectors.toList());
    }

    /**
     * Get the number of already-scanned images.
     */
    public int getScannedCount() {
        return scannedPaths.size();
    }

    /**
     * Detect faces in a batch of images with incremental scanning.
     * Only processes images not previously scanned. Saves progress after each chunk.
     *
     * @param imagePaths all image paths to consider
     * @param progressCallback receives (current, total) progress updates
     * @return newly detected faces
     */
    public List<FaceDetection> detectFacesBatch(List<String> imagePaths, Consumer<Double> progressCallback)
            throws IOException, InterruptedException {
        return detectFacesBatch(imagePaths, progressCallback, false);
    }

    /**
     * Detect faces in a batch of images.
     *
     * @param imagePaths all image paths to consider
     * @param progressCallback receives progress as fraction 0..1
     * @param force if true, rescan all images regardless of cache
     * @return newly detected faces
     */
    public List<FaceDetection> detectFacesBatch(List<String> imagePaths, Consumer<Double> progressCallback, boolean force)
            throws IOException, InterruptedException {

        // Filter to only new paths unless force
        List<String> pathsToScan = force ? new ArrayList<>(imagePaths)
                : filterNewPaths(imagePaths);

        if (pathsToScan.isEmpty()) {
            if (progressCallback != null) progressCallback.accept(1.0);
            return Collections.emptyList();
        }

        int totalToScan = pathsToScan.size();
        int totalImages = imagePaths.size();
        int skipped = totalImages - totalToScan;
        logger.info("FaceRecognitionService",
                "Scanning " + totalToScan + " images (" + skipped + " already scanned)");

        List<FaceDetection> allNewDetections = new ArrayList<>();

        // Process in chunks
        for (int chunkStart = 0; chunkStart < totalToScan; chunkStart += CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, totalToScan);
            List<String> chunk = pathsToScan.subList(chunkStart, chunkEnd);
            final int chunkOffset = chunkStart;

            logger.info("FaceRecognitionService",
                    "Processing chunk " + (chunkStart / CHUNK_SIZE + 1) +
                    " (" + chunk.size() + " images)");

            List<FaceDetection> chunkDetections = runDetectBatch(chunk,
                    progress -> {
                        if (progressCallback != null) {
                            double overallProgress = (skipped + chunkOffset + progress * chunk.size()) / totalImages;
                            progressCallback.accept(overallProgress);
                        }
                    });

            allNewDetections.addAll(chunkDetections);

            // Mark paths as scanned
            scannedPaths.addAll(chunk);

            // Save after each chunk for crash safety
            mergeDetections(chunkDetections);
            saveState();

            logger.info("FaceRecognitionService",
                    "Chunk complete: " + chunkDetections.size() + " faces found. " +
                    "Total: " + faceDetections.size() + " faces");
        }

        if (progressCallback != null) progressCallback.accept(1.0);
        return allNewDetections;
    }

    /**
     * Detect faces using parallel Python workers, each processing a chunk.
     *
     * @param imagePaths all image paths to consider
     * @param numWorkers number of parallel Python processes
     * @param progressCallback receives (current processed, total) counts
     * @param force if true, rescan all images
     * @return newly detected faces
     */
    public List<FaceDetection> detectFacesParallel(List<String> imagePaths, int numWorkers,
                                                     BiConsumer<Integer, Integer> progressCallback,
                                                     boolean force)
            throws IOException, InterruptedException {

        List<String> pathsToScan = force ? new ArrayList<>(imagePaths)
                : filterNewPaths(imagePaths);

        if (pathsToScan.isEmpty()) {
            if (progressCallback != null) progressCallback.accept(imagePaths.size(), imagePaths.size());
            return Collections.emptyList();
        }

        int total = pathsToScan.size();
        int skipped = imagePaths.size() - total;
        logger.info("FaceRecognitionService",
                "Parallel scan: " + total + " images with " + numWorkers + " workers (" + skipped + " skipped)");

        // Split paths into chunks for each worker
        List<List<String>> workerChunks = new ArrayList<>();
        int chunkSize = Math.max(1, (total + numWorkers - 1) / numWorkers);
        for (int i = 0; i < total; i += chunkSize) {
            workerChunks.add(pathsToScan.subList(i, Math.min(i + chunkSize, total)));
        }

        AtomicInteger processedCount = new AtomicInteger(skipped);
        List<FaceDetection> allNewDetections = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        List<Future<List<FaceDetection>>> futures = new ArrayList<>();

        for (List<String> workerPaths : workerChunks) {
            futures.add(executor.submit(() -> {
                // Each worker processes its paths in sub-chunks
                List<FaceDetection> workerDetections = new ArrayList<>();
                for (int subStart = 0; subStart < workerPaths.size(); subStart += CHUNK_SIZE) {
                    int subEnd = Math.min(subStart + CHUNK_SIZE, workerPaths.size());
                    List<String> subChunk = workerPaths.subList(subStart, subEnd);

                    List<FaceDetection> chunkResult = runDetectBatch(subChunk, progress -> {
                        int done = processedCount.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.accept(done, imagePaths.size());
                        }
                        // Decrement because the loop increments per-image via PROGRESS lines,
                        // but we only have chunk-level progress here
                    });

                    workerDetections.addAll(chunkResult);

                    // Save progress
                    synchronized (FaceRecognitionService.this) {
                        scannedPaths.addAll(subChunk);
                        mergeDetections(chunkResult);
                        saveState();
                    }
                }
                return workerDetections;
            }));
        }

        // Collect results
        for (Future<List<FaceDetection>> future : futures) {
            try {
                allNewDetections.addAll(future.get());
            } catch (ExecutionException e) {
                logger.error("FaceRecognitionService",
                        "Worker failed: " + e.getCause().getMessage());
            }
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        return allNewDetections;
    }

    /**
     * Run a single detect-batch Python process for a list of image paths.
     */
    private List<FaceDetection> runDetectBatch(List<String> paths, Consumer<Double> progressCallback)
            throws IOException, InterruptedException {

        double threshold = configService.getFacesConfidenceThreshold();

        Path inputPath = Files.createTempFile("photostat_faces_input_", ".json");
        Path outputPath = Files.createTempFile("photostat_faces_output_", ".json");
        try {
            objectMapper.writeValue(inputPath.toFile(), paths);

            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath, scriptPath.toString(), "detect-batch",
                    inputPath.toString(), outputPath.toString(),
                    String.valueOf(threshold)
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read progress from stderr
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PROGRESS:") && progressCallback != null) {
                            try {
                                String[] parts = line.substring(9).split("/");
                                double current = Double.parseDouble(parts[0]);
                                double total = Double.parseDouble(parts[1]);
                                progressCallback.accept(current / total);
                            } catch (Exception ignored) {
                            }
                        } else {
                            logger.debug("FaceRecognitionService", "Python: " + line);
                        }
                    }
                } catch (IOException e) {
                    logger.debug("FaceRecognitionService", "Error reading stderr: " + e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Read stdout
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            stderrThread.join(5000);

            if (exitCode != 0) {
                throw new IOException("Face detection failed with exit code: " + exitCode + " stdout: " + stdout);
            }

            // Read output file
            if (Files.exists(outputPath) && Files.size(outputPath) > 0) {
                return objectMapper.readValue(
                        outputPath.toFile(), new TypeReference<List<FaceDetection>>() {});
            }

            return Collections.emptyList();
        } finally {
            Files.deleteIfExists(inputPath);
            Files.deleteIfExists(outputPath);
        }
    }

    /**
     * Merge new detections into the master list, deduplicating by faceId.
     */
    private void mergeDetections(List<FaceDetection> newDetections) {
        Set<String> existingIds = faceDetections.stream()
                .map(FaceDetection::getFaceId).collect(Collectors.toSet());
        for (FaceDetection det : newDetections) {
            if (!existingIds.contains(det.getFaceId())) {
                faceDetections.add(det);
                existingIds.add(det.getFaceId());
            }
        }
        rebuildFaceIndex();
    }

    /**
     * Cluster all detected faces.
     */
    public List<FaceCluster> clusterFaces() throws IOException, InterruptedException {
        if (faceDetections.isEmpty()) {
            return Collections.emptyList();
        }

        double threshold = configService.getFacesClusterThreshold();

        // Write face data to temp file
        Path inputPath = Files.createTempFile("photostat_faces_cluster_input_", ".json");
        Path outputPath = Files.createTempFile("photostat_faces_cluster_output_", ".json");
        try {
            objectMapper.writeValue(inputPath.toFile(), faceDetections);

            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath, scriptPath.toString(), "cluster",
                    inputPath.toString(), outputPath.toString(),
                    String.valueOf(threshold)
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Consume stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("FaceRecognitionService", "Python cluster: " + line);
                }
            }

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String stdout = reader.lines().collect(Collectors.joining("\n"));
                logger.info("FaceRecognitionService", "Cluster result: " + stdout);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Face clustering failed with exit code: " + exitCode);
            }

            // Read output
            if (Files.exists(outputPath) && Files.size(outputPath) > 0) {
                List<FaceCluster> newClusters = objectMapper.readValue(
                        outputPath.toFile(), new TypeReference<List<FaceCluster>>() {});

                // Preserve existing person names by matching face IDs
                Map<String, String> existingNames = new HashMap<>();
                for (FaceCluster old : clusters) {
                    if (old.getPersonName() != null && !old.getPersonName().isEmpty()) {
                        for (String faceId : old.getFaceIds()) {
                            existingNames.put(faceId, old.getPersonName());
                        }
                    }
                }

                for (FaceCluster cluster : newClusters) {
                    if (cluster.getPersonName() == null || cluster.getPersonName().isEmpty()) {
                        for (String faceId : cluster.getFaceIds()) {
                            if (existingNames.containsKey(faceId)) {
                                cluster.setPersonName(existingNames.get(faceId));
                                break;
                            }
                        }
                    }
                }

                this.clusters = newClusters;
                resolveClusters();
                saveState();
                return clusters;
            }

            return Collections.emptyList();
        } finally {
            Files.deleteIfExists(inputPath);
            Files.deleteIfExists(outputPath);
        }
    }

    /**
     * Assign a person name to a cluster and update OpenSearch + sidecars.
     */
    public void assignName(String clusterId, String name) throws IOException {
        FaceCluster cluster = getClusterById(clusterId);
        if (cluster == null) return;

        cluster.setPersonName(name);

        // Collect unique image paths from this cluster's faces
        Set<String> imagePaths = new HashSet<>();
        for (String faceId : cluster.getFaceIds()) {
            FaceDetection face = faceIndex.get(faceId);
            if (face != null) {
                imagePaths.add(face.getImagePath());
            }
        }

        // Update each image's persons field in OpenSearch and sidecar
        for (String imagePath : imagePaths) {
            try {
                var metadata = openSearchService.getDocumentByPath(imagePath);
                if (metadata != null) {
                    metadata.addPerson(name);
                    openSearchService.updateDocument(metadata);
                    sidecarService.writeSidecar(metadata);
                }
            } catch (Exception e) {
                logger.error("FaceRecognitionService",
                        "Failed to update person for " + imagePath + ": " + e.getMessage());
            }
        }

        saveState();
    }

    /**
     * Merge multiple clusters into a target cluster.
     */
    public void mergeClusters(String targetId, List<String> sourceIds) {
        FaceCluster target = getClusterById(targetId);
        if (target == null) return;

        for (String sourceId : sourceIds) {
            FaceCluster source = getClusterById(sourceId);
            if (source != null && !source.getClusterId().equals(targetId)) {
                target.getFaceIds().addAll(source.getFaceIds());
                clusters.remove(source);
            }
        }

        resolveClusters();
        saveState();
    }

    public FaceCluster getClusterById(String clusterId) {
        return clusters.stream()
                .filter(c -> c.getClusterId().equals(clusterId))
                .findFirst()
                .orElse(null);
    }

    public FaceDetection getFaceById(String faceId) {
        return faceIndex.get(faceId);
    }

    /**
     * Load state from JSON files on disk.
     */
    public void loadState() {
        try {
            if (Files.exists(faceDataPath) && Files.size(faceDataPath) > 0) {
                faceDetections = objectMapper.readValue(
                        faceDataPath.toFile(), new TypeReference<List<FaceDetection>>() {});
                rebuildFaceIndex();
                // Rebuild scanned paths from existing detections
                scannedPaths.clear();
                for (FaceDetection fd : faceDetections) {
                    scannedPaths.add(fd.getImagePath());
                }
                logger.info("FaceRecognitionService",
                        "Loaded " + faceDetections.size() + " face detections from " + scannedPaths.size() + " images");
            }
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to load face data", e);
            faceDetections = new ArrayList<>();
        }

        try {
            if (Files.exists(clustersPath) && Files.size(clustersPath) > 0) {
                clusters = objectMapper.readValue(
                        clustersPath.toFile(), new TypeReference<List<FaceCluster>>() {});
                resolveClusters();
                logger.info("FaceRecognitionService", "Loaded " + clusters.size() + " clusters");
            }
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to load clusters", e);
            clusters = new ArrayList<>();
        }
    }

    /**
     * Save state to JSON files on disk.
     */
    public void saveState() {
        try {
            objectMapper.writeValue(faceDataPath.toFile(), faceDetections);
            objectMapper.writeValue(clustersPath.toFile(), clusters);
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to save face data", e);
        }
    }

    private void resolveClusters() {
        for (FaceCluster cluster : clusters) {
            List<FaceDetection> faces = new ArrayList<>();
            Set<String> uniquePaths = new HashSet<>();
            for (String faceId : cluster.getFaceIds()) {
                FaceDetection face = faceIndex.get(faceId);
                if (face != null) {
                    faces.add(face);
                    uniquePaths.add(face.getImagePath());
                }
            }
            cluster.setFaces(faces);
            cluster.setPhotoCount(uniquePaths.size());
        }
    }

    private void rebuildFaceIndex() {
        faceIndex.clear();
        for (FaceDetection face : faceDetections) {
            faceIndex.put(face.getFaceId(), face);
        }
    }

    public List<FaceDetection> getFaceDetections() {
        return faceDetections;
    }

    public List<FaceCluster> getClusters() {
        return clusters;
    }

    public Path getFacesDir() {
        return facesDir;
    }

    public Path getScriptPath() {
        return scriptPath;
    }
}
