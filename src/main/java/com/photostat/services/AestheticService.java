package com.photostat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.photostat.models.ImageMetadata;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Scores indexed images for aesthetic / perceptual quality using the
 * containerized IQA-PyTorch backend (docker/aesthetic, port 8003).
 *
 * <p>The score is written to {@link ImageMetadata#getAestheticScore()} — a
 * machine-generated 0..1 value (1.0 = best), separate from the user-authored
 * {@code rating} field. Images are sent to the backend as base64 JPEG bytes, so
 * the container needs no access to the host filesystem.
 */
public class AestheticService {

    private static AestheticService instance;

    private final ConfigService configService;
    private final OpenSearchService openSearchService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Long edge to downsample to before sending. Aesthetic/quality models don't
    // need full resolution, and smaller images keep the batch fast.
    private static final int MAX_IMAGE_SIZE = 1024;

    // HTTP/1.1 only: the default HttpClient attempts an h2c upgrade that can drop
    // the request body against uvicorn (same issue handled in ImageAnalysisService).
    private volatile HttpClient httpClient;

    private AestheticService() {
        this.configService = ConfigService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.logger = LoggingService.getInstance();
    }

    public static synchronized AestheticService getInstance() {
        if (instance == null) {
            instance = new AestheticService();
        }
        return instance;
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return httpClient;
    }

    private String baseUrl() {
        return configService.getAestheticEndpoint().replaceAll("/+$", "");
    }

    /**
     * Query the backend's /health endpoint. Returns the raw JSON body, or throws
     * if the service is unreachable. Used by the Settings "Test connection" button.
     */
    public String getHealthInfo() throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Aesthetic service error: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Health check interrupted", e);
        }
    }

    /** True if the backend /health responds. */
    public boolean isAvailable() {
        try {
            getHealthInfo();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Score a batch of images. Keys are logical ids (file paths); values are the
     * downsampled JPEG bytes. Returns id → normalized score (0..1), skipping any
     * image the backend reported an error for.
     */
    public Map<String, Double> scoreBatch(Map<String, byte[]> images) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode imagesArray = objectMapper.createArrayNode();
        for (Map.Entry<String, byte[]> e : images.entrySet()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", e.getKey());
            item.put("data", Base64.getEncoder().encodeToString(e.getValue()));
            imagesArray.add(item);
        }
        request.set("images", imagesArray);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/score-batch"))
                    // Generous: a large batch on CPU can be slow (fast on GPU).
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();

            HttpResponse<String> response = getHttpClient()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Aesthetic service error: HTTP " + response.statusCode()
                        + " - " + response.body());
            }

            JsonNode parsed = objectMapper.readTree(response.body());
            if (!"ok".equals(parsed.path("status").asText(""))) {
                throw new IOException("Aesthetic service error: "
                        + parsed.path("message").asText("unknown error"));
            }

            Map<String, Double> scores = new LinkedHashMap<>();
            JsonNode results = parsed.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    if (r.has("error")) {
                        logger.warn("AestheticService",
                                "Backend could not score " + r.path("id").asText("?")
                                        + ": " + r.path("error").asText(""));
                        continue;
                    }
                    scores.put(r.path("id").asText(""), r.path("normalized").asDouble());
                }
            }
            return scores;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Aesthetic request interrupted", e);
        }
    }

    /**
     * Score indexed images and write the results back to OpenSearch.
     *
     * @param paths    file paths of indexed images to score
     * @param force    if false, paths that already have an aesthetic score are skipped
     * @param batchSize images per backend call
     * @param progress optional (processed, total) callback; may be null
     * @return number of images successfully scored and updated
     */
    public int scoreCollection(List<String> paths, boolean force, int batchSize,
                               BiConsumer<Integer, Integer> progress) throws IOException {
        String metric = configService.getAestheticMetric();
        int total = paths.size();
        int processed = 0;
        int scored = 0;

        // Documents we've fetched + their downsampled bytes for the current batch.
        Map<String, byte[]> batchBytes = new LinkedHashMap<>();
        Map<String, ImageMetadata> batchDocs = new LinkedHashMap<>();

        for (String path : paths) {
            processed++;
            try {
                ImageMetadata doc = openSearchService.getDocumentByPath(path);
                if (doc == null) {
                    continue;   // not indexed — nothing to update
                }
                if (!force && doc.getAestheticScore() != null) {
                    if (progress != null) progress.accept(processed, total);
                    continue;
                }

                byte[] bytes = downsampleToJpeg(new File(path));
                if (bytes == null) {
                    if (progress != null) progress.accept(processed, total);
                    continue;
                }
                batchBytes.put(path, bytes);
                batchDocs.put(path, doc);
            } catch (Exception e) {
                logger.warn("AestheticService", "Skipping " + path + ": " + e.getMessage());
            }

            if (batchBytes.size() >= batchSize) {
                scored += flushBatch(batchBytes, batchDocs, metric);
            }
            if (progress != null) progress.accept(processed, total);
        }

        // Final partial batch
        scored += flushBatch(batchBytes, batchDocs, metric);
        return scored;
    }

    /**
     * Score images already in hand (e.g. the current selection), writing the
     * score onto each {@link ImageMetadata} object <em>and</em> back to OpenSearch.
     * Mutating the provided objects lets the caller refresh its table without a
     * re-fetch.
     *
     * @param images   images to score (their file_path must be readable on disk)
     * @param force    if false, images that already have a score are skipped
     * @param progress optional (processed, total) callback; may be null
     * @return number of images successfully scored
     */
    public int scoreImages(List<ImageMetadata> images, boolean force,
                           BiConsumer<Integer, Integer> progress) throws IOException {
        String metric = configService.getAestheticMetric();
        int total = images.size();
        int processed = 0;
        int scored = 0;

        Map<String, byte[]> batchBytes = new LinkedHashMap<>();
        Map<String, ImageMetadata> batchDocs = new LinkedHashMap<>();
        final int batchSize = 16;

        for (ImageMetadata doc : images) {
            processed++;
            try {
                if (doc.getFilePath() == null
                        || (!force && doc.getAestheticScore() != null)) {
                    if (progress != null) progress.accept(processed, total);
                    continue;
                }
                byte[] bytes = downsampleToJpeg(new File(doc.getFilePath()));
                if (bytes != null) {
                    batchBytes.put(doc.getFilePath(), bytes);
                    batchDocs.put(doc.getFilePath(), doc);
                }
            } catch (Exception e) {
                logger.warn("AestheticService", "Skipping " + doc.getFilePath() + ": " + e.getMessage());
            }

            if (batchBytes.size() >= batchSize) {
                scored += flushBatch(batchBytes, batchDocs, metric);
            }
            if (progress != null) progress.accept(processed, total);
        }
        scored += flushBatch(batchBytes, batchDocs, metric);
        return scored;
    }

    /** Score the pending batch, write scores onto the docs, and bulk-update OpenSearch. */
    private int flushBatch(Map<String, byte[]> batchBytes, Map<String, ImageMetadata> batchDocs,
                           String metric) throws IOException {
        if (batchBytes.isEmpty()) {
            return 0;
        }
        Map<String, Double> scores = scoreBatch(batchBytes);

        List<ImageMetadata> updated = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            ImageMetadata doc = batchDocs.get(e.getKey());
            if (doc != null) {
                doc.setAestheticScore(e.getValue());
                doc.setAestheticMetric(metric);
                updated.add(doc);
            }
        }
        if (!updated.isEmpty()) {
            openSearchService.bulkIndex(updated);
        }

        int n = updated.size();
        batchBytes.clear();
        batchDocs.clear();
        return n;
    }

    /**
     * Read an image and re-encode it as a JPEG no larger than MAX_IMAGE_SIZE on
     * the long edge. Returns null if the image can't be read.
     */
    byte[] downsampleToJpeg(File imageFile) {
        try {
            BufferedImage original = ImageIO.read(imageFile);
            if (original == null) {
                return null;
            }
            int w = original.getWidth();
            int h = original.getHeight();
            int newW = w;
            int newH = h;
            if (w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) {
                if (w >= h) {
                    newW = MAX_IMAGE_SIZE;
                    newH = Math.max(1, (int) ((double) h / w * MAX_IMAGE_SIZE));
                } else {
                    newH = MAX_IMAGE_SIZE;
                    newW = Math.max(1, (int) ((double) w / h * MAX_IMAGE_SIZE));
                }
            }

            BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(original, 0, 0, newW, newH, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            logger.warn("AestheticService", "Failed to read " + imageFile + ": " + e.getMessage());
            return null;
        }
    }
}
