package com.photostat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Service for analyzing images using Claude's vision API.
 */
public class ImageAnalysisService {

    private static ImageAnalysisService instance;
    private final ConfigService configService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private ImageAnalysisService() {
        this.configService = ConfigService.getInstance();
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
     * Analyze an image using Claude's vision API.
     */
    public AnalysisResult analyzeImage(String imagePath) {
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

            byte[] imageBytes = Files.readAllBytes(path);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // Determine media type
            String mediaType = getMediaType(imagePath);
            if (mediaType == null) {
                result.setError("Unsupported image format");
                return result;
            }

            // Build the request
            String requestBody = buildRequestBody(base64Image, mediaType);

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

            logger.info("ImageAnalysisService", "Analysis complete. Tags: " + result.getTags().size() +
                    ", Persons: " + result.getPersons().size() + ", Rating: " + result.getRating());

        } catch (IOException | InterruptedException e) {
            logger.error("ImageAnalysisService", "Failed to analyze image", e);
            result.setError("Analysis failed: " + e.getMessage());
        }

        return result;
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
        return """
            Analyze this photograph and provide metadata in JSON format. Include:

            1. **tags**: Array of descriptive tags for the image. Include:
               - Photography style (e.g., "Portrait", "Landscape", "Street Photography", "Pet Photography", "Macro", "Architecture", "Food Photography")
               - Subject matter (e.g., "Dog", "Cat", "Bird", "Flower", "Building", "Car")
               - Mood/atmosphere (e.g., "Moody", "Bright", "Dramatic", "Peaceful")
               - Technical aspects if notable (e.g., "Black and White", "Bokeh", "Long Exposure", "HDR")
               - Season/weather if visible (e.g., "Winter", "Snow", "Sunset", "Rainy")
               - Setting (e.g., "Indoor", "Outdoor", "Urban", "Rural", "Beach")

            2. **persons**: Array of descriptive identifiers for people visible in the image. If no people are visible, use an empty array. Don't use names unless they are clearly identifiable public figures. Instead use descriptions like "woman in red dress", "elderly man", "child", etc.

            3. **place**: A single string describing the location if identifiable. This could be a specific place name, city, type of venue (e.g., "Restaurant", "Park", "Beach"), or null if not determinable.

            4. **rating**: Rate the overall quality of the photograph from * to ***** (1 to 5 stars) based on:
               - Composition and framing
               - Technical quality (sharpness, exposure, focus)
               - Artistic value and creativity
               - Color/tonal quality
               - Overall impact and interest

               Use this scale:
               - * = Poor (significant technical issues, bad composition)
               - ** = Below average (noticeable issues, weak composition)
               - *** = Average (decent execution, standard composition)
               - **** = Good (strong composition, good technique, visually appealing)
               - ***** = Excellent (exceptional composition, masterful technique, highly impactful)

            Respond with ONLY valid JSON in this exact format:
            {
                "tags": ["tag1", "tag2", "tag3"],
                "persons": [],
                "place": "Location or null",
                "rating": "***"
            }
            """;
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
     * Check if the API key is configured.
     */
    public boolean isConfigured() {
        String apiKey = configService.getClaudeApiKey();
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
