package com.photostat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * Service for analyzing images using Claude or Gemini vision APIs.
 */
public class ImageAnalysisService {

    private static ImageAnalysisService instance;
    private final ConfigService configService;
    private final SidecarService sidecarService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_IMAGE_SIZE = 1024;  // Max dimension in pixels for API optimization
    private static final float JPEG_QUALITY = 0.85f; // JPEG compression quality

    private ImageAnalysisService() {
        this.configService = ConfigService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static synchronized ImageAnalysisService getInstance() {
        if (instance == null) {
            instance = new ImageAnalysisService();
        }
        return instance;
    }

    /**
     * Result of image analysis containing extracted metadata.
     */
    public static class AnalysisResult {
        private List<String> tags = new ArrayList<>();
        private List<String> persons = new ArrayList<>();
        private String place;
        private String rating;
        private String error;

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<String> getPersons() { return persons; }
        public void setPersons(List<String> persons) { this.persons = persons; }
        public String getPlace() { return place; }
        public void setPlace(String place) { this.place = place; }
        public String getRating() { return rating; }
        public void setRating(String rating) { this.rating = rating; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public boolean hasError() { return error != null && !error.isEmpty(); }
    }

    /**
     * Analyze an image using the configured AI provider (Claude or Gemini).
     */
    public AnalysisResult analyzeImage(String imagePath) {
        String provider = configService.getAiProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            return analyzeImageWithGemini(imagePath);
        } else {
            return analyzeImageWithClaude(imagePath);
        }
    }

    /**
     * Analyze an image using Claude's vision API.
     */
    private AnalysisResult analyzeImageWithClaude(String imagePath) {
        AnalysisResult result = new AnalysisResult();

        String apiKey = configService.getClaudeApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            result.setError("Claude API key not configured. Please set it in Settings.");
            return result;
        }

        try {
            // Read and encode the image
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                result.setError("Image file not found: " + imagePath);
                return result;
            }

            // Determine media type
            String mediaType = getMediaType(imagePath);
            if (mediaType == null) {
                result.setError("Unsupported image format");
                return result;
            }

            // Optimize image for API (resize and compress)
            byte[] optimizedBytes = optimizeImageForApi(path.toFile());
            if (optimizedBytes == null) {
                result.setError("Failed to process image");
                return result;
            }

            String base64Image = Base64.getEncoder().encodeToString(optimizedBytes);
            logger.debug("ImageAnalysisService", "Optimized image size: " + (optimizedBytes.length / 1024) + " KB");

            // Build the request (always send as JPEG since we convert)
            String requestBody = buildRequestBody(base64Image, "image/jpeg");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLAUDE_API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            logger.info("ImageAnalysisService", "Sending image to Claude API: " + imagePath);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("ImageAnalysisService", "API error: " + response.statusCode() + " - " + response.body());
                result.setError("API error: " + response.statusCode());
                return result;
            }

            // Parse the response
            parseResponse(response.body(), result);

            // Save analysis cache if successful
            if (!result.hasError()) {
                saveAnalysisCache(imagePath);
            }

            logger.info("ImageAnalysisService", "Analysis complete. Tags: " + result.getTags().size() +
                    ", Persons: " + result.getPersons().size() + ", Rating: " + result.getRating());

        } catch (IOException | InterruptedException e) {
            logger.error("ImageAnalysisService", "Failed to analyze image", e);
            result.setError("Analysis failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Analyze an image using Google's Gemini vision API.
     */
    private AnalysisResult analyzeImageWithGemini(String imagePath) {
        AnalysisResult result = new AnalysisResult();

        String apiKey = configService.getGeminiApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            result.setError("Gemini API key not configured. Please set it in Settings.");
            return result;
        }

        try {
            // Read and optimize the image
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                result.setError("Image file not found: " + imagePath);
                return result;
            }

            // Optimize image for API (resize and compress)
            byte[] optimizedBytes = optimizeImageForApi(path.toFile());
            if (optimizedBytes == null) {
                result.setError("Failed to process image");
                return result;
            }

            logger.debug("ImageAnalysisService", "Optimized image size for Gemini: " + (optimizedBytes.length / 1024) + " KB");

            // Create Gemini client with API key
            Client client = Client.builder()
                    .apiKey(apiKey)
                    .build();

            // Build content with image and prompt
            Content content = Content.fromParts(
                    Part.fromBytes(optimizedBytes, "image/jpeg"),
                    Part.fromText(getAnalysisPrompt())
            );

            String model = configService.getGeminiModel();
            logger.info("ImageAnalysisService", "Sending image to Gemini API (" + model + "): " + imagePath);

            // Generate content
            GenerateContentResponse response = client.models.generateContent(model, content, null);

            // Get response text
            String responseText = response.text();
            logger.debug("ImageAnalysisService", "Gemini raw response: " + responseText);

            if (responseText == null || responseText.isEmpty()) {
                result.setError("Empty response from Gemini API");
                return result;
            }

            // Parse the response (same format as Claude)
            parseGeminiResponse(responseText, result);
            logger.debug("ImageAnalysisService", "Parsed result - Tags: " + result.getTags() +
                    ", Persons: " + result.getPersons() + ", Place: " + result.getPlace() +
                    ", Rating: " + result.getRating() + ", Error: " + result.getError());

            // Save analysis cache if successful
            if (!result.hasError()) {
                saveAnalysisCache(imagePath);
            }

            logger.info("ImageAnalysisService", "Gemini analysis complete. Tags: " + result.getTags().size() +
                    ", Persons: " + result.getPersons().size() + ", Rating: " + result.getRating());

        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to analyze image with Gemini", e);
            result.setError("Gemini analysis failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Parse Gemini response text into AnalysisResult.
     */
    private void parseGeminiResponse(String responseText, AnalysisResult result) {
        try {
            // Extract JSON from the response (same logic as Claude)
            String json = extractJson(responseText);

            if (json != null) {
                JsonNode analysis = objectMapper.readTree(json);

                // Parse tags
                JsonNode tags = analysis.path("tags");
                if (tags.isArray()) {
                    List<String> tagList = new ArrayList<>();
                    for (JsonNode tag : tags) {
                        tagList.add(tag.asText());
                    }
                    result.setTags(tagList);
                }

                // Parse persons
                JsonNode persons = analysis.path("persons");
                if (persons.isArray()) {
                    List<String> personList = new ArrayList<>();
                    for (JsonNode person : persons) {
                        personList.add(person.asText());
                    }
                    result.setPersons(personList);
                }

                // Parse place
                JsonNode place = analysis.path("place");
                if (!place.isNull() && !place.isMissingNode()) {
                    String placeText = place.asText();
                    if (!"null".equalsIgnoreCase(placeText) && !placeText.isEmpty()) {
                        result.setPlace(placeText);
                    }
                }

                // Parse rating
                JsonNode rating = analysis.path("rating");
                if (!rating.isNull() && !rating.isMissingNode()) {
                    result.setRating(rating.asText());
                }
            } else {
                result.setError("Could not parse JSON from Gemini response");
            }
        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to parse Gemini response", e);
            result.setError("Failed to parse Gemini response: " + e.getMessage());
        }
    }

    private String getMediaType(String imagePath) {
        String lower = imagePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    /**
     * Optimize an image for the Claude Vision API by resizing and compressing.
     * This reduces API costs and improves response time.
     */
    private byte[] optimizeImageForApi(File imageFile) {
        try {
            // Read the original image
            BufferedImage originalImage = ImageIO.read(imageFile);
            if (originalImage == null) {
                logger.error("ImageAnalysisService", "Failed to read image: " + imageFile.getPath());
                return null;
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            // Calculate new dimensions maintaining aspect ratio
            int newWidth = originalWidth;
            int newHeight = originalHeight;

            if (originalWidth > MAX_IMAGE_SIZE || originalHeight > MAX_IMAGE_SIZE) {
                if (originalWidth > originalHeight) {
                    newWidth = MAX_IMAGE_SIZE;
                    newHeight = (int) ((double) originalHeight / originalWidth * MAX_IMAGE_SIZE);
                } else {
                    newHeight = MAX_IMAGE_SIZE;
                    newWidth = (int) ((double) originalWidth / originalHeight * MAX_IMAGE_SIZE);
                }
                logger.debug("ImageAnalysisService", "Resizing image from " + originalWidth + "x" + originalHeight +
                        " to " + newWidth + "x" + newHeight);
            }

            // Create resized image
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resizedImage.createGraphics();

            // Use high-quality rendering hints
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Fill with white background (for transparent images)
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, newWidth, newHeight);

            // Draw the resized image
            g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            g2d.dispose();

            // Compress as JPEG
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                logger.error("ImageAnalysisService", "No JPEG writer available");
                return null;
            }

            ImageWriter writer = writers.next();
            ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream);
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            writer.write(null, new IIOImage(resizedImage, null, null), param);
            writer.dispose();
            ios.close();

            return outputStream.toByteArray();

        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to optimize image", e);
            return null;
        }
    }

    private String buildRequestBody(String base64Image, String mediaType) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", configService.getClaudeModel());
        root.put("max_tokens", 1024);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        // Image content
        ObjectNode imageContent = objectMapper.createObjectNode();
        imageContent.put("type", "image");
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64Image);
        imageContent.set("source", source);
        content.add(imageContent);

        // Text prompt
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", getAnalysisPrompt());
        content.add(textContent);

        message.set("content", content);
        messages.add(message);
        root.set("messages", messages);

        return objectMapper.writeValueAsString(root);
    }

    private String getAnalysisPrompt() {
        return configService.getClaudeAnalysisPrompt();
    }

    private void parseResponse(String responseBody, AnalysisResult result) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();

                // Extract JSON from the response (it might have markdown code blocks)
                String json = extractJson(text);

                if (json != null) {
                    JsonNode analysis = objectMapper.readTree(json);

                    // Parse tags
                    JsonNode tags = analysis.path("tags");
                    if (tags.isArray()) {
                        List<String> tagList = new ArrayList<>();
                        for (JsonNode tag : tags) {
                            tagList.add(tag.asText());
                        }
                        result.setTags(tagList);
                    }

                    // Parse persons
                    JsonNode persons = analysis.path("persons");
                    if (persons.isArray()) {
                        List<String> personList = new ArrayList<>();
                        for (JsonNode person : persons) {
                            personList.add(person.asText());
                        }
                        result.setPersons(personList);
                    }

                    // Parse place
                    JsonNode place = analysis.path("place");
                    if (!place.isNull() && !place.isMissingNode()) {
                        String placeText = place.asText();
                        if (!"null".equalsIgnoreCase(placeText) && !placeText.isEmpty()) {
                            result.setPlace(placeText);
                        }
                    }

                    // Parse rating
                    JsonNode rating = analysis.path("rating");
                    if (!rating.isNull() && !rating.isMissingNode()) {
                        result.setRating(rating.asText());
                    }
                } else {
                    result.setError("Could not parse JSON from response");
                }
            } else {
                result.setError("Empty response from API");
            }
        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to parse response", e);
            result.setError("Failed to parse response: " + e.getMessage());
        }
    }

    private String extractJson(String text) {
        // Try to find JSON in the text (may be wrapped in markdown code blocks)
        String trimmed = text.trim();

        // Remove markdown code block if present
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }

        trimmed = trimmed.trim();

        // Verify it looks like JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // Try to find JSON object in the text
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }

    /**
     * Check if the API key is configured for the current provider.
     */
    public boolean isConfigured() {
        String provider = configService.getAiProvider();
        String apiKey;
        if ("gemini".equalsIgnoreCase(provider)) {
            apiKey = configService.getGeminiApiKey();
        } else {
            apiKey = configService.getClaudeApiKey();
        }
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Get the name of the currently configured AI provider.
     */
    public String getProviderName() {
        String provider = configService.getAiProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            return "Gemini";
        } else {
            return "Claude";
        }
    }

    /**
     * Compute a combined hash of provider + model + prompt + image info (size + modification time).
     * This single hash detects any change that would require re-analysis.
     */
    private String computeAnalysisHash(String imagePath) {
        try {
            Path path = Path.of(imagePath);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);

            // Get provider and model
            String provider = configService.getAiProvider();
            String model;
            if ("gemini".equalsIgnoreCase(provider)) {
                model = configService.getGeminiModel();
            } else {
                model = configService.getClaudeModel();
            }
            String prompt = configService.getClaudeAnalysisPrompt();

            // Combine: provider + model + prompt + image path + file size + modification time
            String hashInput = provider + "|" + model + "|" + prompt + "|" + imagePath + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(hashInput.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // Use first 16 chars for brevity
        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to compute analysis hash", e);
            return null;
        }
    }

    /**
     * Check if analysis is cached and still valid.
     * Returns true if the stored hash matches current hash (image unchanged, same model, same prompt).
     */
    public boolean isAnalysisCached(String imagePath) {
        SidecarService.SidecarData sidecar = sidecarService.getAnalysisCache(imagePath);
        if (sidecar == null || !sidecar.hasAnalysisCache()) {
            return false;
        }

        String currentHash = computeAnalysisHash(imagePath);
        if (currentHash == null) {
            return false;
        }

        boolean matches = currentHash.equals(sidecar.getAnalysisHash());
        if (matches) {
            logger.debug("ImageAnalysisService", "Analysis cache valid for " + imagePath);
        } else {
            logger.debug("ImageAnalysisService", "Analysis cache invalid for " + imagePath);
        }
        return matches;
    }

    /**
     * Save analysis hash to sidecar file.
     */
    private void saveAnalysisCache(String imagePath) {
        String analysisHash = computeAnalysisHash(imagePath);
        if (analysisHash != null) {
            sidecarService.updateAnalysisCache(imagePath, analysisHash);
        }
    }
}
