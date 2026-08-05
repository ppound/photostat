package com.photostat.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photostat.models.FaceCluster;
import com.photostat.models.FaceDetection;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private final Path scannedPathsFile;
    private final Path scriptPath;

    private static final int CHUNK_SIZE = 500;

    // Docker (HTTP) mode tuning. Images are optimized client-side and POSTed in
    // small sub-batches so payloads stay bounded and progress stays responsive.
    private static final int DOCKER_SUB_BATCH = 8;
    private static final int DOCKER_IMAGE_MAX_SIZE = 1600;
    private static final float DOCKER_IMAGE_QUALITY = 0.9f;

    /** Shared HTTP client for Docker mode (lazily created). */
    private volatile HttpClient httpClient;

    private List<FaceDetection> faceDetections = Collections.synchronizedList(new ArrayList<>());
    private List<FaceCluster> clusters = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, FaceDetection> faceIndex = new ConcurrentHashMap<>();
    /** Set of image paths that have already been scanned for faces. */
    private final Set<String> scannedPaths = ConcurrentHashMap.newKeySet();

    /** Live progress tracking — updated during detectFacesBatch for UI display. */
    private volatile String currentScanFile = null;
    private volatile int currentScanCount = 0;
    private volatile int totalScanCount = 0;

    public String getCurrentScanFile() { return currentScanFile; }
    public int getCurrentScanCount()   { return currentScanCount; }
    public int getTotalScanCount()     { return totalScanCount; }

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
        this.scannedPathsFile = facesDir.resolve("scanned_paths.json");
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

    /** True when faces should run against the Dockerized HTTP service. */
    private boolean isDockerMode() {
        return "docker".equalsIgnoreCase(configService.getFacesMode());
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    // Force HTTP/1.1: the uvicorn/h11 services don't support the
                    // HTTP/2 (h2c) upgrade that HttpClient attempts by default,
                    // which otherwise causes the request body to be dropped.
                    httpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return httpClient;
    }

    /** Open the detection backend appropriate for the configured mode. */
    private FaceBackend openBackend() throws IOException {
        return isDockerMode() ? new DockerFaceBackend() : new PythonWorker();
    }

    /**
     * A face-detection backend that processes a batch of image paths.
     * Implemented by the local Python worker and the Docker HTTP service.
     */
    private interface FaceBackend extends Closeable {
        List<FaceDetection> detectBatch(List<String> paths, double threshold,
                                        Consumer<Double> progressCallback) throws IOException;
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
     * Fetch the Docker faces service /health response, or throw on failure.
     */
    private String fetchDockerHealth() throws IOException {
        String url = configService.getFacesEndpoint().replaceAll("/+$", "") + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Health check interrupted", e);
        }
    }

    /**
     * Check if the configured faces backend is available
     * (Python + InsightFace locally, or the Docker service over HTTP).
     */
    public boolean isPythonAvailable() {
        if (isDockerMode()) {
            try {
                return fetchDockerHealth().contains("\"status\"");
            } catch (IOException e) {
                logger.debug("FaceRecognitionService", "Docker faces health check failed: " + e.getMessage());
                return false;
            }
        }
        Process process = null;
        try {
            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString(), "check");
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0 && output.contains("\"status\": \"ok\"")) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("FaceRecognitionService", "Python check failed: " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return false;
    }

    /**
     * Get version info from the Python environment.
     */
    public String getPythonVersionInfo() {
        if (isDockerMode()) {
            try {
                return fetchDockerHealth();
            } catch (IOException e) {
                return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
            }
        }
        Process process = null;
        try {
            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString(), "check");
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "{\"status\": \"error\", \"message\": \"timeout\"}";
            }
            return output;
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
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
        double threshold = configService.getFacesConfidenceThreshold();

        // Process all chunks with a single backend (local worker loads the model once;
        // the Docker service is long-lived).
        try (FaceBackend worker = openBackend()) {
            for (int chunkStart = 0; chunkStart < totalToScan; chunkStart += CHUNK_SIZE) {
                int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, totalToScan);
                List<String> chunk = pathsToScan.subList(chunkStart, chunkEnd);
                final int chunkOffset = chunkStart;

                logger.info("FaceRecognitionService",
                        "Processing chunk " + (chunkStart / CHUNK_SIZE + 1) +
                        " (" + chunk.size() + " images)");

                List<FaceDetection> chunkDetections = worker.detectBatch(chunk, threshold,
                        progress -> {
                            if (progressCallback != null) {
                                double overallProgress = (skipped + chunkOffset + progress * chunk.size()) / totalImages;
                                // Update live tracking fields for UI display
                                int approxIdx = Math.min((int) (progress * chunk.size()), chunk.size() - 1);
                                if (approxIdx >= 0) currentScanFile = chunk.get(approxIdx);
                                currentScanCount = (int) (skipped + chunkOffset + progress * chunk.size());
                                totalScanCount = totalImages;
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

        // Docker mode is backed by a single container that serializes inference, so
        // parallel Java workers wouldn't help (and the model isn't thread-safe across
        // concurrent requests). Route through the sequential batch path instead.
        if (isDockerMode()) {
            int total = imagePaths.size();
            return detectFacesBatch(imagePaths, frac -> {
                if (progressCallback != null) {
                    progressCallback.accept((int) Math.round(frac * total), total);
                }
            }, force);
        }

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
        double threshold = configService.getFacesConfidenceThreshold();

        for (List<String> workerPaths : workerChunks) {
            futures.add(executor.submit(() -> {
                // Each Java thread owns one persistent Python worker (model loads once per thread)
                List<FaceDetection> workerDetections = new ArrayList<>();
                try (PythonWorker worker = new PythonWorker()) {
                    for (int subStart = 0; subStart < workerPaths.size(); subStart += CHUNK_SIZE) {
                        int subEnd = Math.min(subStart + CHUNK_SIZE, workerPaths.size());
                        List<String> subChunk = workerPaths.subList(subStart, subEnd);

                        List<FaceDetection> chunkResult = worker.detectBatch(subChunk, threshold, progress -> {
                            int done = processedCount.incrementAndGet();
                            if (progressCallback != null) {
                                progressCallback.accept(done, imagePaths.size());
                            }
                        });

                        workerDetections.addAll(chunkResult);

                        // Save progress
                        synchronized (FaceRecognitionService.this) {
                            scannedPaths.addAll(subChunk);
                            mergeDetections(chunkResult);
                            saveState();
                        }
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
     * Persistent Python worker — loads InsightFace once and handles multiple detect-batch
     * requests over stdin/stdout, eliminating per-chunk model startup cost.
     */
    private class PythonWorker implements FaceBackend {
        private final Process process;
        private final PrintWriter stdin;
        private final BufferedReader stdout;
        private final Thread stderrThread;
        private final AtomicReference<Consumer<String>> progressHandler = new AtomicReference<>(null);
        // Flipped to true once the ready signal arrives; controls stderr log level
        private final AtomicReference<Boolean> initDone = new AtomicReference<>(false);

        PythonWorker() throws IOException {
            String pythonPath = configService.getFacesPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString(), "worker");
            pb.redirectErrorStream(false);
            this.process = pb.start();
            this.stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // Stderr thread: logs at INFO during model init so CUDA/cuDNN errors are visible,
            // then switches to DEBUG for normal run-time output.
            this.stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PROGRESS:")) {
                            Consumer<String> handler = progressHandler.get();
                            if (handler != null) {
                                handler.accept(line.substring(9)); // "n/total"
                            }
                        } else if (!initDone.get()) {
                            // Log everything at INFO during init — this is where onnxruntime
                            // prints CUDA fallback warnings (e.g. missing cuDNN DLLs)
                            logger.info("FaceRecognitionService", "Python worker init: " + line);
                        } else {
                            logger.debug("FaceRecognitionService", "Python worker: " + line);
                        }
                    }
                } catch (IOException e) {
                    logger.debug("FaceRecognitionService", "Stderr read error: " + e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Block until the Python worker signals it has loaded the model.
            // InsightFace/onnxruntime prints debug lines (e.g. "Applied providers: [...]")
            // directly to stdout during init, so we log those at INFO and keep reading.
            String readyLine = null;
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.contains("\"ready\"")) {
                    readyLine = line;
                    break;
                }
                logger.info("FaceRecognitionService", "Python worker init: " + line);
            }
            if (readyLine == null) {
                process.destroyForcibly();
                throw new IOException("Python worker closed before sending ready signal");
            }
            // Log which providers the model actually loaded on (GPU vs CPU)
            try {
                Map<String, Object> readyMsg = objectMapper.readValue(
                        readyLine, new TypeReference<Map<String, Object>>() {});
                Object providers = readyMsg.get("providers");
                if (providers != null) {
                    boolean gpu = providers.toString().contains("CUDA");
                    logger.info("FaceRecognitionService",
                            "Python worker ready — providers: " + providers +
                            (gpu ? " ✓ GPU" : " — CPU only (check CUDA installation)"));
                } else {
                    logger.info("FaceRecognitionService", "Python worker ready");
                }
            } catch (Exception ignored) {
                logger.info("FaceRecognitionService", "Python worker ready");
            }
            initDone.set(true); // switch stderr logging to DEBUG from here on
        }

        @Override
        public List<FaceDetection> detectBatch(List<String> paths, double threshold,
                                         Consumer<Double> progressCallback) throws IOException {
            progressHandler.set(progress -> {
                if (progressCallback != null) {
                    try {
                        String[] parts = progress.split("/");
                        double current = Double.parseDouble(parts[0]);
                        double total = Double.parseDouble(parts[1]);
                        progressCallback.accept(current / total);
                    } catch (Exception ignored) {}
                }
            });
            try {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("command", "detect-batch");
                request.put("paths", paths);
                request.put("threshold", threshold);
                stdin.println(objectMapper.writeValueAsString(request));

                String responseLine = stdout.readLine();
                if (responseLine == null) {
                    throw new IOException("Python worker closed unexpectedly");
                }

                Map<String, Object> response = objectMapper.readValue(
                        responseLine, new TypeReference<Map<String, Object>>() {});
                String status = (String) response.get("status");
                if (!"ok".equals(status)) {
                    throw new IOException("Python worker error: " + response.get("message"));
                }

                Object facesObj = response.get("faces");
                if (facesObj == null) {
                    return Collections.emptyList();
                }
                return objectMapper.convertValue(facesObj, new TypeReference<List<FaceDetection>>() {});
            } finally {
                progressHandler.set(null);
            }
        }

        @Override
        public void close() {
            try {
                stdin.println(objectMapper.writeValueAsString(Map.of("command", "shutdown")));
            } catch (Exception ignored) {}
            stdin.close();
            try {
                stderrThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    /**
     * Face-detection backend that calls the Dockerized HTTP service. Reads each
     * image on the Java side, optimizes it, and sends base64 bytes — so the
     * container needs no access to the host filesystem. Stateless: there is no
     * process to manage, so close() is a no-op.
     */
    private class DockerFaceBackend implements FaceBackend {

        @Override
        public List<FaceDetection> detectBatch(List<String> paths, double threshold,
                                               Consumer<Double> progressCallback) throws IOException {
            String url = configService.getFacesEndpoint().replaceAll("/+$", "") + "/faces/detect-batch";
            List<FaceDetection> results = new ArrayList<>();
            int total = paths.size();
            int processed = 0;

            for (int start = 0; start < total; start += DOCKER_SUB_BATCH) {
                int end = Math.min(start + DOCKER_SUB_BATCH, total);
                List<String> sub = paths.subList(start, end);

                // Build the request payload: optimized base64 images for this sub-batch.
                // We downscale before sending, so detections come back in the
                // optimized image's coordinate space; remember each image's scale
                // factor so we can map the boxes back to original-image pixels.
                List<Map<String, String>> images = new ArrayList<>();
                Map<String, double[]> scales = new HashMap<>();
                for (String path : sub) {
                    ImageOptimizer.Result optimized = ImageOptimizer.optimizeToJpegScaled(
                            new File(path), DOCKER_IMAGE_MAX_SIZE, DOCKER_IMAGE_QUALITY);
                    if (optimized == null) {
                        // Unreadable / unsupported (e.g. RAW) — skip, like cv2 returning None.
                        logger.debug("FaceRecognitionService", "Skipping unreadable image: " + path);
                        continue;
                    }
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("id", path);
                    item.put("data", Base64.getEncoder().encodeToString(optimized.jpeg));
                    images.add(item);
                    scales.put(path, new double[]{optimized.scaleX, optimized.scaleY});
                }

                if (!images.isEmpty()) {
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("threshold", threshold);
                    request.put("images", images);
                    List<FaceDetection> subFaces = postDetectBatch(url, request);
                    for (FaceDetection face : subFaces) {
                        scaleToOriginal(face, scales.get(face.getImagePath()));
                    }
                    results.addAll(subFaces);
                }

                processed = end;
                if (progressCallback != null) {
                    progressCallback.accept((double) processed / total);
                }
            }
            return results;
        }

        private List<FaceDetection> postDetectBatch(String url, Map<String, Object> request) throws IOException {
            try {
                String body = objectMapper.writeValueAsString(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = getHttpClient()
                        .send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Faces service error: HTTP " + response.statusCode()
                            + " - " + response.body());
                }

                Map<String, Object> parsed = objectMapper.readValue(
                        response.body(), new TypeReference<Map<String, Object>>() {});
                if (!"ok".equals(parsed.get("status"))) {
                    throw new IOException("Faces service error: " + parsed.get("message"));
                }
                Object facesObj = parsed.get("faces");
                if (facesObj == null) {
                    return Collections.emptyList();
                }
                return objectMapper.convertValue(facesObj, new TypeReference<List<FaceDetection>>() {});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Faces service request interrupted", e);
            }
        }

        @Override
        public void close() {
            // Nothing to clean up — the HTTP service is long-lived.
        }
    }

    /**
     * Map a detection's bounding box from optimized-image space back to the
     * original image. The Docker backend downscales images before sending them,
     * so InsightFace reports boxes in the optimized image's pixel space; without
     * this, face crops (read from the original file) land near the top-left.
     * No-op when {@code scale} is null (image was skipped) or effectively 1.
     */
    private static void scaleToOriginal(FaceDetection face, double[] scale) {
        if (scale == null) {
            return;
        }
        double sx = scale[0];
        double sy = scale[1];
        face.setX((int) Math.round(face.getX() * sx));
        face.setY((int) Math.round(face.getY() * sy));
        face.setWidth((int) Math.round(face.getWidth() * sx));
        face.setHeight((int) Math.round(face.getHeight() * sy));
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
     * Cluster all detected faces using the configured backend.
     */
    public List<FaceCluster> clusterFaces() throws IOException, InterruptedException {
        if (faceDetections.isEmpty()) {
            return Collections.emptyList();
        }

        double threshold = configService.getFacesClusterThreshold();
        List<FaceCluster> newClusters = isDockerMode()
                ? clusterViaDocker(threshold)
                : clusterViaPython(threshold);

        if (newClusters == null || newClusters.isEmpty()) {
            return Collections.emptyList();
        }

        applyNewClusters(newClusters);
        return clusters;
    }

    /**
     * Cluster faces by spawning the local Python script (file-based I/O).
     */
    private List<FaceCluster> clusterViaPython(double threshold) throws IOException, InterruptedException {
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
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try {
                // Read combined stdout/stderr
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("FaceRecognitionService", "Python cluster: " + line);
                    }
                }

                boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Face clustering timed out after 5 minutes");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Face clustering failed with exit code: " + process.exitValue());
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            if (Files.exists(outputPath) && Files.size(outputPath) > 0) {
                return objectMapper.readValue(
                        outputPath.toFile(), new TypeReference<List<FaceCluster>>() {});
            }
            return Collections.emptyList();
        } finally {
            Files.deleteIfExists(inputPath);
            Files.deleteIfExists(outputPath);
        }
    }

    /**
     * Cluster faces via the Dockerized HTTP service.
     */
    private List<FaceCluster> clusterViaDocker(double threshold) throws IOException {
        String url = configService.getFacesEndpoint().replaceAll("/+$", "") + "/faces/cluster";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("threshold", threshold);
        request.put("faces", faceDetections);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = getHttpClient()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Faces clustering error: HTTP " + response.statusCode()
                        + " - " + response.body());
            }

            Map<String, Object> parsed = objectMapper.readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            if (!"ok".equals(parsed.get("status"))) {
                throw new IOException("Faces clustering error: " + parsed.get("message"));
            }
            Object clustersObj = parsed.get("clusters");
            if (clustersObj == null) {
                return Collections.emptyList();
            }
            return objectMapper.convertValue(clustersObj, new TypeReference<List<FaceCluster>>() {});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Faces clustering request interrupted", e);
        }
    }

    /**
     * Apply freshly computed clusters: preserve existing person names (matched by
     * face ID), replace the cluster list, resolve, and persist.
     */
    private void applyNewClusters(List<FaceCluster> newClusters) {
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

        this.clusters.clear();
        this.clusters.addAll(newClusters);
        resolveClusters();
        saveState();
    }

    /**
     * Assign a person name to a cluster and update OpenSearch + sidecars.
     */
    public void assignName(String clusterId, String name) throws IOException {
        assignName(clusterId, name, null);
    }

    public void assignName(String clusterId, String name, java.util.function.Consumer<double[]> progressCallback) throws IOException {
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

        // Update each image's persons field in OpenSearch and sidecar. Skip images
        // that already carry this name so re-saving a mostly-named cluster doesn't
        // re-write every OpenSearch doc and sidecar for no change.
        int total = imagePaths.size();
        int current = 0;
        int updated = 0;
        for (String imagePath : imagePaths) {
            try {
                var metadata = openSearchService.getDocumentByPath(imagePath);
                if (metadata != null) {
                    List<String> existing = metadata.getPersons();
                    if (existing == null || !existing.contains(name)) {
                        metadata.addPerson(name);
                        openSearchService.updateDocument(metadata);
                        sidecarService.writeSidecar(metadata);
                        updated++;
                    }
                }
            } catch (Exception e) {
                logger.error("FaceRecognitionService",
                        "Failed to update person for " + imagePath + ": " + e.getMessage());
            }
            current++;
            if (progressCallback != null) {
                progressCallback.accept(new double[]{current, total});
            }
        }

        logger.info("FaceRecognitionService", "assignName '" + name + "': " + updated
                + " of " + total + " images updated (" + (total - updated) + " already named)");

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
    public synchronized void loadState() {
        try {
            if (Files.exists(faceDataPath) && Files.size(faceDataPath) > 0) {
                List<FaceDetection> loaded = objectMapper.readValue(
                        faceDataPath.toFile(), new TypeReference<List<FaceDetection>>() {});
                faceDetections.clear();
                faceDetections.addAll(loaded);
                rebuildFaceIndex();
                logger.info("FaceRecognitionService", "Loaded " + faceDetections.size() + " face detections");
            }
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to load face data", e);
        }

        // Load scanned paths — persisted separately so images with zero faces are remembered.
        // Falls back to deriving from face detections for backward compatibility.
        scannedPaths.clear();
        try {
            if (Files.exists(scannedPathsFile) && Files.size(scannedPathsFile) > 2) {
                scannedPaths.addAll(objectMapper.readValue(
                        scannedPathsFile.toFile(), new TypeReference<Set<String>>() {}));
                logger.info("FaceRecognitionService",
                        "Loaded " + scannedPaths.size() + " scanned paths (" +
                        faceDetections.size() + " with faces)");
            } else {
                // Backward compat: derive from detections (misses face-free images until next scan)
                for (FaceDetection fd : faceDetections) {
                    scannedPaths.add(fd.getImagePath());
                }
                if (!scannedPaths.isEmpty()) {
                    logger.info("FaceRecognitionService",
                            "Derived " + scannedPaths.size() + " scanned paths from detections " +
                            "(no scanned_paths.json yet — face-free images will be re-scanned once)");
                }
            }
        } catch (IOException e) {
            logger.warn("FaceRecognitionService",
                    "Failed to load scanned_paths.json, deriving from detections: " + e.getMessage());
            for (FaceDetection fd : faceDetections) {
                scannedPaths.add(fd.getImagePath());
            }
        }

        try {
            if (Files.exists(clustersPath) && Files.size(clustersPath) > 0) {
                List<FaceCluster> loaded = objectMapper.readValue(
                        clustersPath.toFile(), new TypeReference<List<FaceCluster>>() {});
                clusters.clear();
                clusters.addAll(loaded);
                resolveClusterCounts();
                logger.info("FaceRecognitionService", "Loaded " + clusters.size() + " clusters");
            }
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to load clusters", e);
        }
    }

    /**
     * Save state to JSON files on disk.
     * Refuses to overwrite existing data with an empty list to prevent accidental data loss.
     * Creates a backup before writing.
     */
    public void saveState() {
        try {
            // Don't overwrite existing face data with an empty list
            if (faceDetections.isEmpty() && Files.exists(faceDataPath) && Files.size(faceDataPath) > 2) {
                logger.warn("FaceRecognitionService",
                        "Refusing to overwrite face_data.json with empty list — existing file has data");
                // Still persist scanned paths so face-free images aren't re-scanned
                objectMapper.writeValue(scannedPathsFile.toFile(), scannedPaths);
                return;
            }

            // Back up before writing
            if (Files.exists(faceDataPath) && Files.size(faceDataPath) > 0) {
                Files.copy(faceDataPath, faceDataPath.resolveSibling("face_data.json.bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(clustersPath) && Files.size(clustersPath) > 0) {
                Files.copy(clustersPath, clustersPath.resolveSibling("clusters.json.bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            objectMapper.writeValue(faceDataPath.toFile(), faceDetections);
            objectMapper.writeValue(clustersPath.toFile(), clusters);
            objectMapper.writeValue(scannedPathsFile.toFile(), scannedPaths);
        } catch (IOException e) {
            logger.error("FaceRecognitionService", "Failed to save face data", e);
        }
    }

    /**
     * Lightweight resolve: only compute photo counts for all clusters (no face list population).
     * Used during loadState() to avoid resolving face lists for thousands of clusters.
     */
    private void resolveClusterCounts() {
        for (FaceCluster cluster : clusters) {
            Set<String> uniquePaths = new HashSet<>();
            for (String faceId : cluster.getFaceIds()) {
                FaceDetection face = faceIndex.get(faceId);
                if (face != null) {
                    uniquePaths.add(face.getImagePath());
                }
            }
            cluster.setPhotoCount(uniquePaths.size());
        }
    }

    /**
     * Full resolve: populate face lists and photo counts for all clusters.
     * Used after clustering when all data is fresh.
     */
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

    /**
     * Resolve face lists and photo counts for a specific page of clusters.
     * Called on demand by the UI when loading a page of clusters.
     */
    public void resolveClustersPage(List<FaceCluster> page) {
        for (FaceCluster cluster : page) {
            if (cluster.getFaces() != null && !cluster.getFaces().isEmpty()) {
                continue; // Already resolved
            }
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
