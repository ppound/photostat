package com.photostat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service for generating images using the Luma AI API.
 */
public class LumaService {

    private static LumaService instance;
    private final ConfigService configService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final String BASE_URL = "https://api.lumalabs.ai/dream-machine/v1";
    private static final int MAX_IMAGE_SIZE = 1024;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 120; // 6 minutes max

    private LumaService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static synchronized LumaService getInstance() {
        if (instance == null) {
            instance = new LumaService();
        }
        return instance;
    }

    public boolean isConfigured() {
        String apiKey = configService.getLumaApiKey();
        String imgbbKey = configService.getImgbbApiKey();
        return apiKey != null && !apiKey.trim().isEmpty()
                && imgbbKey != null && !imgbbKey.trim().isEmpty();
    }

    /**
     * Test the API connection by listing generations.
     * Returns null on success, error message on failure.
     */
    public String testConnection() {
        String apiKey = configService.getLumaApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "API key not configured";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/generations"))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return null; // success
            } else if (response.statusCode() == 401) {
                return "Invalid API key";
            } else {
                return "API error: " + response.statusCode() + " - " + response.body();
            }
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    /**
     * Upload a local image to ImgBB and return the hosted URL.
     * Luma API requires publicly accessible URLs for reference images.
     * ImgBB is the recommended approach (used by Luma's own ComfyUI plugin).
     */
    public String uploadImage(File imageFile) throws IOException, InterruptedException {
        String imgbbApiKey = configService.getImgbbApiKey();
        if (imgbbApiKey == null || imgbbApiKey.trim().isEmpty()) {
            throw new IOException("ImgBB API key not configured. Get a free key at https://api.imgbb.com/ and set it in Settings (Image Generation tab).");
        }

        // Optimize the image before uploading
        byte[] imageBytes = optimizeImageForUpload(imageFile);
        String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);

        logger.info("LumaService", "Uploading image to ImgBB: " + imageFile.getName() +
                " (" + (imageBytes.length / 1024) + " KB)");

        // ImgBB API: POST https://api.imgbb.com/1/upload with form data
        String boundary = "----PhotoStatBoundary" + System.currentTimeMillis();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // key parameter
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"key\"\r\n\r\n".getBytes());
        baos.write(imgbbApiKey.trim().getBytes());
        baos.write("\r\n".getBytes());

        // image parameter (base64)
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"image\"\r\n\r\n".getBytes());
        baos.write(base64Image.getBytes());
        baos.write("\r\n".getBytes());

        // name parameter
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"name\"\r\n\r\n".getBytes());
        String imageName = imageFile.getName().replaceAll("\\.[^.]+$", "");
        baos.write(imageName.getBytes());
        baos.write("\r\n".getBytes());

        // expiration parameter (seconds) — auto-delete after 10 minutes
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"expiration\"\r\n\r\n".getBytes());
        baos.write("600".getBytes());
        baos.write("\r\n".getBytes());

        baos.write(("--" + boundary + "--\r\n").getBytes());

        byte[] body = baos.toByteArray();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.imgbb.com/1/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("ImgBB upload failed: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());

        if (!responseJson.has("data") || !responseJson.get("data").has("url")) {
            throw new IOException("Unexpected ImgBB response: " + response.body());
        }

        String imageUrl = responseJson.get("data").get("url").asText();
        logger.info("LumaService", "Image uploaded: " + imageUrl);
        return imageUrl;
    }

    /**
     * Submit an image generation request.
     * Returns the generation ID for polling.
     */
    public String submitGeneration(String prompt, List<String> imageUrls, LumaOptions options)
            throws IOException, InterruptedException {
        String apiKey = configService.getLumaApiKey();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "photon-1");
        requestBody.put("prompt", prompt);
        requestBody.put("aspect_ratio", options.aspectRatio);

        // Add image references based on reference type
        if (imageUrls != null && !imageUrls.isEmpty()) {
            switch (options.refType) {
                case "style_ref":
                    ArrayNode styleRefs = objectMapper.createArrayNode();
                    for (String url : imageUrls) {
                        ObjectNode ref = objectMapper.createObjectNode();
                        ref.put("url", url);
                        ref.put("weight", options.refWeight);
                        styleRefs.add(ref);
                    }
                    requestBody.set("style_ref", styleRefs);
                    break;

                case "modify_image_ref":
                    // modify_image_ref only supports a single image
                    ObjectNode modifyRef = objectMapper.createObjectNode();
                    modifyRef.put("url", imageUrls.get(0));
                    modifyRef.put("weight", options.refWeight);
                    requestBody.set("modify_image_ref", modifyRef);
                    break;

                case "image_ref":
                default:
                    ArrayNode imageRefs = objectMapper.createArrayNode();
                    for (String url : imageUrls) {
                        ObjectNode ref = objectMapper.createObjectNode();
                        ref.put("url", url);
                        ref.put("weight", options.refWeight);
                        imageRefs.add(ref);
                    }
                    requestBody.set("image_ref", imageRefs);
                    break;
            }
        }

        String bodyStr = objectMapper.writeValueAsString(requestBody);
        logger.info("LumaService", "Submitting generation request: " + prompt);
        logger.debug("LumaService", "Request body: " + bodyStr);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/generations/image"))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Generation request failed: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        String generationId = responseJson.get("id").asText();
        logger.info("LumaService", "Generation submitted: " + generationId);
        return generationId;
    }

    /**
     * Poll for generation completion.
     * Returns the result containing status and asset URL.
     */
    public PollResult pollGeneration(String generationId) throws IOException, InterruptedException {
        String apiKey = configService.getLumaApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/generations/" + generationId))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Poll failed: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        PollResult result = new PollResult();
        result.state = responseJson.get("state").asText();

        if (responseJson.has("failure_reason") && !responseJson.get("failure_reason").isNull()) {
            result.failureReason = responseJson.get("failure_reason").asText();
        }

        if (responseJson.has("assets") && !responseJson.get("assets").isNull()) {
            JsonNode assets = responseJson.get("assets");
            if (assets.has("image")) {
                result.imageUrl = assets.get("image").asText();
            }
        }

        return result;
    }

    /**
     * Download an image from a URL to a local file.
     */
    public void downloadImage(String imageUrl, File destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed: " + response.statusCode());
        }

        Files.createDirectories(destination.getParentFile().toPath());
        try (InputStream is = response.body()) {
            Files.copy(is, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        logger.info("LumaService", "Image downloaded to: " + destination.getAbsolutePath());
    }

    /**
     * High-level orchestrator: upload source images → submit generation → poll → download result.
     */
    public GenerationResult generateImage(String prompt, List<File> sourceImages, LumaOptions options,
                                          File outputFile, Consumer<String> statusCallback) {
        GenerationResult result = new GenerationResult();

        try {
            // Step 1: Upload source images
            List<String> imageUrls = new ArrayList<>();
            for (int i = 0; i < sourceImages.size(); i++) {
                File sourceImage = sourceImages.get(i);
                if (statusCallback != null) {
                    statusCallback.accept("Uploading image " + (i + 1) + " of " + sourceImages.size() + "...");
                }
                String url = uploadImage(sourceImage);
                imageUrls.add(url);
                logger.info("LumaService", "Uploaded: " + sourceImage.getName() + " → " + url);
            }

            // Step 2: Submit generation
            if (statusCallback != null) {
                statusCallback.accept("Submitting generation request...");
            }
            String generationId = submitGeneration(prompt, imageUrls, options);
            result.generationId = generationId;

            // Step 3: Poll for completion
            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
                Thread.sleep(POLL_INTERVAL_MS);

                PollResult poll = pollGeneration(generationId);

                if (statusCallback != null) {
                    String stateDisplay = poll.state.substring(0, 1).toUpperCase() + poll.state.substring(1);
                    statusCallback.accept(stateDisplay + "... (" + ((attempt + 1) * POLL_INTERVAL_MS / 1000) + "s)");
                }

                if ("completed".equals(poll.state)) {
                    if (poll.imageUrl != null) {
                        // Step 4: Download
                        if (statusCallback != null) {
                            statusCallback.accept("Downloading generated image...");
                        }
                        downloadImage(poll.imageUrl, outputFile);
                        result.success = true;
                        result.outputFile = outputFile;
                        return result;
                    } else {
                        result.error = "Generation completed but no image URL returned";
                        return result;
                    }
                } else if ("failed".equals(poll.state)) {
                    result.error = "Generation failed: " + (poll.failureReason != null ? poll.failureReason : "Unknown error");
                    return result;
                }
                // queued or dreaming — keep polling
            }

            result.error = "Generation timed out after " + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + " seconds";

        } catch (IOException | InterruptedException e) {
            logger.error("LumaService", "Generation failed", e);
            result.error = "Generation failed: " + e.getMessage();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return result;
    }

    /**
     * Optimize an image for upload: resize large images and compress as JPEG.
     */
    private byte[] optimizeImageForUpload(File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            // Fallback: just read raw bytes
            return Files.readAllBytes(imageFile.toPath());
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Resize if larger than MAX_IMAGE_SIZE
        if (width > MAX_IMAGE_SIZE || height > MAX_IMAGE_SIZE) {
            double scale = Math.min((double) MAX_IMAGE_SIZE / width, (double) MAX_IMAGE_SIZE / height);
            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resized.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
            g2d.dispose();
            image = resized;
        }

        // Compress as JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        writer.dispose();

        return baos.toByteArray();
    }

    // Inner classes for options and results

    public static class LumaOptions {
        public String aspectRatio = "1:1";
        public String refType = "image_ref"; // image_ref, style_ref, modify_image_ref
        public double refWeight = 0.85;
    }

    public static class GenerationResult {
        public boolean success = false;
        public File outputFile;
        public String error;
        public String generationId;
    }

    public static class PollResult {
        public String state;
        public String imageUrl;
        public String failureReason;
    }
}
