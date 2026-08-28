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
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for analyzing images using Claude, Gemini, Ollama-compatible APIs, or Moondream (local).
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

    // Moondream persistent worker
    private MoondreamWorker moondreamWorker;
    private final Path moondreamScriptPath;

    /**
     * HTTP client for the Dockerized Moondream service (lazily created).
     * Forced to HTTP/1.1: the uvicorn/h11 service doesn't support the HTTP/2
     * (h2c) upgrade HttpClient attempts by default, which drops the request body.
     */
    private volatile HttpClient moondreamDockerClient;

    private ImageAnalysisService() {
        this.configService = ConfigService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // Set up Moondream script path and extract from resources
        String userHome = System.getProperty("user.home");
        this.moondreamScriptPath = Path.of(userHome, ".photostat", "photostat_moondream.py");
        extractMoondreamScript();
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

        // Token usage tracking (for cost estimation)
        private int inputTokens = 0;
        private int outputTokens = 0;

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

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public int getTotalTokens() { return inputTokens + outputTokens; }
    }

    /**
     * Analyze an image using the configured AI provider (Claude, Gemini, or Moondream).
     */
    public AnalysisResult analyzeImage(String imagePath) {
        String provider = configService.getAiProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            return analyzeImageWithGemini(imagePath);
        } else if ("ollama".equalsIgnoreCase(provider)) {
            return analyzeImageWithOllama(imagePath);
        } else if ("moondream".equalsIgnoreCase(provider)) {
            return analyzeImageWithMoondream(imagePath);
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

            // Extract token usage if available
            if (response.usageMetadata().isPresent()) {
                var usage = response.usageMetadata().get();
                if (usage.promptTokenCount().isPresent()) {
                    result.setInputTokens(usage.promptTokenCount().get());
                }
                if (usage.candidatesTokenCount().isPresent()) {
                    result.setOutputTokens(usage.candidatesTokenCount().get());
                }
                logger.debug("ImageAnalysisService", "Gemini tokens - input: " + result.getInputTokens() +
                        ", output: " + result.getOutputTokens());
            }

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
     * Analyze an image using an OpenAI-compatible chat endpoint such as local Ollama.
     */
    private AnalysisResult analyzeImageWithOllama(String imagePath) {
        AnalysisResult result = new AnalysisResult();

        String baseUrl = configService.getOllamaBaseUrl();
        String model = configService.getOllamaModel();
        if (baseUrl == null || baseUrl.trim().isEmpty() || model == null || model.trim().isEmpty()) {
            result.setError("Ollama base URL or model not configured. Please set them in Settings.");
            return result;
        }

        try {
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                result.setError("Image file not found: " + imagePath);
                return result;
            }

            byte[] optimizedBytes = optimizeImageForApi(path.toFile());
            if (optimizedBytes == null) {
                result.setError("Failed to process image");
                return result;
            }

            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(optimizedBytes);
            String requestBody = buildOpenAiCompatibleVisionRequest(model, dataUrl);
            String endpoint = normalizeOpenAiBaseUrl(baseUrl) + "/chat/completions";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            String apiKey = configService.getOllamaApiKey();
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
            }

            logger.info("ImageAnalysisService", "Sending image to Ollama-compatible API (" + model + "): " + imagePath);

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                logger.error("ImageAnalysisService", "Ollama API error: " + response.statusCode() + " - " + response.body());
                result.setError("API error: " + response.statusCode());
                return result;
            }

            parseOpenAiCompatibleResponse(response.body(), result);
            if (!result.hasError()) {
                saveAnalysisCache(imagePath);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("ImageAnalysisService", "Failed to analyze image with Ollama-compatible API", e);
            result.setError("Analysis failed: " + e.getMessage());
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

    /**
     * Analyze an image using the local Moondream model via a persistent Python worker.
     * Uses multiple simple questions instead of a single complex prompt, since Moondream
     * is a small model that works best with short, direct queries.
     */
    private AnalysisResult analyzeImageWithMoondream(String imagePath) {
        AnalysisResult result = new AnalysisResult();

        try {
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                result.setError("Image file not found: " + imagePath);
                return result;
            }

            // Optimize image for the model.
            byte[] optimizedBytes = optimizeImageForApi(path.toFile());
            if (optimizedBytes == null) {
                result.setError("Failed to process image");
                return result;
            }

            logger.info("ImageAnalysisService", "Sending image to Moondream ("
                    + (isMoondreamDockerMode() ? "docker" : "local") + "): " + imagePath);

            // Ask multiple simple questions in one call — encodes the image only once
            List<String> prompts = List.of(
                    "List descriptive tags for this photo separated by commas. Include the type of photography, main subjects, mood, and setting.",
                    "Are there any people in this image? If yes, briefly describe each person. If no, say \"none\".",
                    "What is the location or type of place shown in this image? Give a short answer.",
                    "Rate the photographic quality of this image from 1 to 5 stars. Consider composition, sharpness, and artistic value. Reply with just the number of stars like: ***"
            );

            List<String> responses = runMoondreamMulti(optimizedBytes, prompts);

            String tagsResponse = responses.size() > 0 ? responses.get(0) : "";
            String personsResponse = responses.size() > 1 ? responses.get(1) : "";
            String placeResponse = responses.size() > 2 ? responses.get(2) : "";
            String ratingResponse = responses.size() > 3 ? responses.get(3) : "";

            logger.debug("ImageAnalysisService", "Moondream tags: " + tagsResponse);
            logger.debug("ImageAnalysisService", "Moondream persons: " + personsResponse);
            logger.debug("ImageAnalysisService", "Moondream place: " + placeResponse);
            logger.debug("ImageAnalysisService", "Moondream rating: " + ratingResponse);

            parseMoondreamResults(result, tagsResponse, personsResponse, placeResponse, ratingResponse);

            // Save analysis cache if successful
            if (!result.hasError()) {
                saveAnalysisCache(imagePath);
            }

            logger.info("ImageAnalysisService", "Moondream analysis complete. Tags: " + result.getTags().size() +
                    ", Persons: " + result.getPersons().size() + ", Rating: " + result.getRating());

        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to analyze image with Moondream", e);
            result.setError("Moondream analysis failed: " + e.getMessage());
            // Null out worker on failure so it recreates on next call
            synchronized (this) {
                if (moondreamWorker != null) {
                    moondreamWorker.close();
                    moondreamWorker = null;
                }
            }
        }

        return result;
    }

    /**
     * Parse Moondream's simple text responses into an AnalysisResult.
     */
    private void parseMoondreamResults(AnalysisResult result, String tagsResponse,
                                        String personsResponse, String placeResponse, String ratingResponse) {
        // Parse tags — split on commas, clean up
        if (tagsResponse != null && !tagsResponse.isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (String tag : tagsResponse.split(",")) {
                String cleaned = tag.trim()
                        .replaceAll("^[-•*\\d.]+\\s*", "") // remove list markers
                        .replaceAll("^\"(.*)\"$", "$1");    // remove quotes
                if (!cleaned.isEmpty() && cleaned.length() < 50) {
                    // Capitalize first letter
                    tags.add(cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1));
                }
            }
            result.setTags(tags);
        }

        // Parse persons
        if (personsResponse != null && !personsResponse.isEmpty()) {
            String lower = personsResponse.toLowerCase().trim();
            if (!lower.contains("none") && !lower.contains("no people") && !lower.contains("no one")
                    && !lower.startsWith("no") && !lower.contains("there are no") && !lower.contains("there is no")) {
                // Strip leading "yes, " / "yes " prefix that Moondream often adds
                String stripped = personsResponse.trim().replaceFirst("(?i)^yes[,.:;!]?\\s*", "");
                List<String> persons = new ArrayList<>();
                // Split on common delimiters
                for (String person : stripped.split("[,;]|\\band\\b")) {
                    String cleaned = person.trim()
                            .replaceAll("^[-•*\\d.]+\\s*", "")
                            .replaceAll("^\"(.*)\"$", "$1");
                    if (!cleaned.isEmpty() && cleaned.length() < 80) {
                        persons.add(cleaned);
                    }
                }
                result.setPersons(persons);
            }
        }

        // Parse place
        if (placeResponse != null && !placeResponse.isEmpty()) {
            String cleaned = placeResponse.trim();
            String lower = cleaned.toLowerCase();
            if (!lower.contains("cannot determine") && !lower.contains("not possible")
                    && !lower.equals("unknown") && !lower.equals("null") && !lower.equals("n/a")) {
                // Take first sentence if response is long
                int period = cleaned.indexOf('.');
                if (period > 0 && period < 80) {
                    cleaned = cleaned.substring(0, period);
                } else if (cleaned.length() > 80) {
                    cleaned = cleaned.substring(0, 80);
                }
                result.setPlace(cleaned);
            }
        }

        // Parse rating — look for star patterns or numbers
        if (ratingResponse != null && !ratingResponse.isEmpty()) {
            String rating = null;
            // Count asterisks
            long starCount = ratingResponse.chars().filter(c -> c == '*').count();
            if (starCount >= 1 && starCount <= 5) {
                rating = "*".repeat((int) starCount);
            } else {
                // Look for digit 1-5
                for (char c : ratingResponse.toCharArray()) {
                    if (c >= '1' && c <= '5') {
                        rating = "*".repeat(c - '0');
                        break;
                    }
                }
            }
            if (rating == null) {
                rating = "***"; // default to average
            }
            result.setRating(rating);
        }
    }

    /** True when Moondream should run against the Dockerized HTTP service. */
    private boolean isMoondreamDockerMode() {
        return "docker".equalsIgnoreCase(configService.getMoondreamMode());
    }

    private HttpClient getMoondreamDockerClient() {
        if (moondreamDockerClient == null) {
            synchronized (this) {
                if (moondreamDockerClient == null) {
                    moondreamDockerClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return moondreamDockerClient;
    }

    /**
     * Run a multi-prompt Moondream analysis against the configured backend
     * (local Python worker or the Docker HTTP service), encoding the image once.
     */
    private List<String> runMoondreamMulti(byte[] optimizedBytes, List<String> prompts) throws IOException {
        if (isMoondreamDockerMode()) {
            return moondreamDockerAnalyze(optimizedBytes, prompts);
        }
        // Local: the worker reads the image from a temp file.
        Path tempImage = Files.createTempFile("photostat_moondream_", ".jpg");
        try {
            Files.write(tempImage, optimizedBytes);
            MoondreamWorker worker = getMoondreamWorker();
            return worker.analyzeMulti(tempImage.toString(), prompts);
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    /**
     * Analyze an image via the Dockerized Moondream service. Sends the optimized
     * image as base64 so the container needs no host filesystem access.
     */
    private List<String> moondreamDockerAnalyze(byte[] optimizedBytes, List<String> prompts) throws IOException {
        String url = configService.getMoondreamEndpoint().replaceAll("/+$", "") + "/analyze";

        ObjectNode request = objectMapper.createObjectNode();
        request.put("image", Base64.getEncoder().encodeToString(optimizedBytes));
        ArrayNode promptsArray = objectMapper.createArrayNode();
        for (String prompt : prompts) {
            promptsArray.add(prompt);
        }
        request.set("prompts", promptsArray);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    // Generous: multi-prompt Moondream inference can take many
                    // minutes on CPU (it's near-instant on GPU).
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();

            HttpResponse<String> response = getMoondreamDockerClient()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Moondream service error: HTTP " + response.statusCode()
                        + " - " + response.body());
            }

            JsonNode parsed = objectMapper.readTree(response.body());
            if (!"ok".equals(parsed.path("status").asText(""))) {
                throw new IOException("Moondream service error: "
                        + parsed.path("message").asText("unknown error"));
            }

            List<String> results = new ArrayList<>();
            JsonNode responses = parsed.path("responses");
            if (responses.isArray()) {
                for (JsonNode r : responses) {
                    results.add(r.asText(""));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Moondream request interrupted", e);
        }
    }

    /**
     * Get or create the Moondream worker (lazy init with crash recovery).
     */
    private synchronized MoondreamWorker getMoondreamWorker() throws IOException {
        if (moondreamWorker == null) {
            moondreamWorker = new MoondreamWorker();
        }
        return moondreamWorker;
    }

    /**
     * Extract the Moondream Python script from JAR resources to ~/.photostat/.
     */
    private void extractMoondreamScript() {
        try (InputStream is = getClass().getResourceAsStream("/photostat_moondream.py")) {
            if (is == null) {
                logger.debug("ImageAnalysisService", "Moondream Python script not found in resources");
                return;
            }
            Files.createDirectories(moondreamScriptPath.getParent());
            Files.copy(is, moondreamScriptPath, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("ImageAnalysisService", "Extracted Moondream script to: " + moondreamScriptPath);
        } catch (IOException e) {
            logger.error("ImageAnalysisService", "Failed to extract Moondream script", e);
        }
    }

    /**
     * Fetch the Docker Moondream service /health response, or throw on failure.
     */
    private String fetchMoondreamHealth() throws IOException {
        String url = configService.getMoondreamEndpoint().replaceAll("/+$", "") + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = getMoondreamDockerClient()
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
     * Check if the configured Moondream backend is available
     * (Python deps locally, or the Docker service over HTTP).
     */
    /**
     * True if the Dockerised Moondream analysis service answers its /health endpoint.
     *
     * <p>Unlike {@link #isMoondreamAvailable()} this ignores the configured
     * backend mode, so the Services tab can report on the container itself even
     * when the app is set to local Python.
     */
    public boolean isDockerServiceHealthy() {
        try {
            return fetchMoondreamHealth().contains("\"status\"");
        } catch (IOException e) {
            logger.debug("ImageAnalysisService", "Docker Moondream health check failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isMoondreamAvailable() {
        if (isMoondreamDockerMode()) {
            try {
                return fetchMoondreamHealth().contains("\"status\"");
            } catch (IOException e) {
                logger.debug("ImageAnalysisService", "Docker Moondream health check failed: " + e.getMessage());
                return false;
            }
        }
        Process process = null;
        try {
            String pythonPath = configService.getMoondreamPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, moondreamScriptPath.toString(), "check");
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return finished && process.exitValue() == 0 && output.contains("\"status\": \"ok\"");
        } catch (Exception e) {
            logger.debug("ImageAnalysisService", "Moondream check failed: " + e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Get Moondream version/device info.
     */
    public String getMoondreamVersionInfo() {
        if (isMoondreamDockerMode()) {
            try {
                return fetchMoondreamHealth();
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
        Process process = null;
        try {
            String pythonPath = configService.getMoondreamPythonPath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, moondreamScriptPath.toString(), "check");
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Timeout checking Moondream";
            }
            return output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Shutdown the Moondream worker process. Call on application exit.
     */
    public synchronized void shutdown() {
        if (moondreamWorker != null) {
            moondreamWorker.close();
            moondreamWorker = null;
        }
    }

    /**
     * Persistent Moondream Python worker — loads model once and handles multiple
     * analyze requests over stdin/stdout JSON protocol.
     */
    private class MoondreamWorker {
        private final Process process;
        private final PrintWriter stdin;
        private final BufferedReader stdout;
        private final Thread stderrThread;
        private final List<String> stderrLines = java.util.Collections.synchronizedList(new ArrayList<>());

        MoondreamWorker() throws IOException {
            String pythonPath = configService.getMoondreamPythonPath();
            logger.info("ImageAnalysisService", "Launching Moondream worker: " + pythonPath + " " + moondreamScriptPath);
            ProcessBuilder pb = new ProcessBuilder(pythonPath, moondreamScriptPath.toString(), "worker");
            pb.redirectErrorStream(false);
            this.process = pb.start();
            this.stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // Stderr thread — captures output for error reporting
            this.stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrLines.add(line);
                        logger.info("ImageAnalysisService", "Moondream worker: " + line);
                    }
                } catch (IOException e) {
                    logger.debug("ImageAnalysisService", "Moondream stderr read error: " + e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Wait for ready signal with timeout (model loading can take a while)
            logger.info("ImageAnalysisService", "Starting Moondream worker (model loading may take 10-60s)...");
            String readyLine = null;
            String line;
            long deadline = System.currentTimeMillis() + 120_000; // 2 minute timeout
            while ((line = stdout.readLine()) != null) {
                if (line.contains("\"ready\"")) {
                    readyLine = line;
                    break;
                }
                // Check if it's an error response from the script
                if (line.contains("\"error\"")) {
                    process.destroyForcibly();
                    throw new IOException("Moondream worker failed to start: " + line);
                }
                logger.info("ImageAnalysisService", "Moondream worker init: " + line);
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly();
                    throw new IOException("Moondream worker timed out during model loading (120s)");
                }
            }
            if (readyLine == null) {
                process.destroyForcibly();
                // Wait briefly for stderr thread to capture error output
                try { stderrThread.join(2000); } catch (InterruptedException ignored) {}
                String stderrOutput = stderrLines.isEmpty() ? "no error output"
                        : String.join("\n", stderrLines.subList(Math.max(0, stderrLines.size() - 10), stderrLines.size()));
                throw new IOException("Moondream worker closed before sending ready signal. Stderr: " + stderrOutput);
            }

            // Log device info
            try {
                JsonNode readyMsg = objectMapper.readTree(readyLine);
                String device = readyMsg.path("device").asText("unknown");
                logger.info("ImageAnalysisService", "Moondream worker ready — device: " + device);
            } catch (Exception ignored) {
                logger.info("ImageAnalysisService", "Moondream worker ready");
            }
        }

        String analyze(String imagePath, String prompt) throws IOException {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("command", "analyze");
            request.put("image_path", imagePath);
            request.put("prompt", prompt);
            stdin.println(objectMapper.writeValueAsString(request));

            String responseLine = stdout.readLine();
            if (responseLine == null) {
                throw new IOException("Moondream worker closed unexpectedly");
            }

            JsonNode response = objectMapper.readTree(responseLine);
            String status = response.path("status").asText("");
            if (!"ok".equals(status)) {
                throw new IOException("Moondream worker error: " + response.path("message").asText("unknown error"));
            }

            return response.path("response").asText("");
        }

        /**
         * Encode image once and answer multiple questions — much faster than separate analyze calls.
         */
        List<String> analyzeMulti(String imagePath, List<String> prompts) throws IOException {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("command", "analyze_multi");
            request.put("image_path", imagePath);
            ArrayNode promptsArray = objectMapper.createArrayNode();
            for (String prompt : prompts) {
                promptsArray.add(prompt);
            }
            request.set("prompts", promptsArray);
            stdin.println(objectMapper.writeValueAsString(request));

            String responseLine = stdout.readLine();
            if (responseLine == null) {
                throw new IOException("Moondream worker closed unexpectedly");
            }

            JsonNode response = objectMapper.readTree(responseLine);
            String status = response.path("status").asText("");
            if (!"ok".equals(status)) {
                throw new IOException("Moondream worker error: " + response.path("message").asText("unknown error"));
            }

            List<String> results = new ArrayList<>();
            JsonNode responses = response.path("responses");
            if (responses.isArray()) {
                for (JsonNode r : responses) {
                    results.add(r.asText(""));
                }
            }
            return results;
        }

        void close() {
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

    String getMediaType(String imagePath) {
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
            // Read the image efficiently using subsampling
            BufferedImage originalImage = null;
            try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(imageFile)) {
                Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    javax.imageio.ImageReader reader = readers.next();
                    reader.setInput(iis, true, true);
                    
                    int origWidth = reader.getWidth(0);
                    int origHeight = reader.getHeight(0);
                    
                    int scale = Math.max(1, Math.min(origWidth / MAX_IMAGE_SIZE, origHeight / MAX_IMAGE_SIZE));
                    int subsampling = 1;
                    while (subsampling * 2 <= scale) {
                        subsampling *= 2;
                    }
                    
                    javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                    if (subsampling > 1) {
                        param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    }
                    
                    originalImage = reader.read(0, param);
                    reader.dispose();
                }
            }

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
            try {
                // Use high-quality rendering hints
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Fill with white background (for transparent images)
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, newWidth, newHeight);

                // Draw the resized image
                g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            } finally {
                g2d.dispose();
            }

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

    private String buildOpenAiCompatibleVisionRequest(String model, String imageDataUrl) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.2);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", getAnalysisPrompt());
        content.add(textContent);

        ObjectNode imageContent = objectMapper.createObjectNode();
        imageContent.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", imageDataUrl);
        imageContent.set("image_url", imageUrl);
        content.add(imageContent);

        message.set("content", content);
        messages.add(message);
        root.set("messages", messages);

        return objectMapper.writeValueAsString(root);
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

    private void parseOpenAiCompatibleResponse(String responseBody, AnalysisResult result) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode contentNode = choice.path("message").path("content");
            String text;
            if (contentNode.isArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode node : contentNode) {
                    String part = node.path("text").asText("");
                    if (!part.isBlank()) {
                        parts.add(part);
                    }
                }
                text = String.join("\n", parts);
            } else {
                text = contentNode.asText("");
            }

            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                result.setInputTokens(usage.path("prompt_tokens").asInt(0));
                result.setOutputTokens(usage.path("completion_tokens").asInt(0));
            }

            String json = extractJson(text);
            if (json == null) {
                result.setError("Could not parse JSON from response");
                return;
            }

            JsonNode analysis = objectMapper.readTree(json);
            applyParsedAnalysis(analysis, result);
        } catch (Exception e) {
            logger.error("ImageAnalysisService", "Failed to parse Ollama-compatible response", e);
            result.setError("Failed to parse response: " + e.getMessage());
        }
    }

    private void applyParsedAnalysis(JsonNode analysis, AnalysisResult result) {
        JsonNode tags = analysis.path("tags");
        if (tags.isArray()) {
            List<String> tagList = new ArrayList<>();
            for (JsonNode tag : tags) {
                tagList.add(tag.asText());
            }
            result.setTags(tagList);
        }

        JsonNode persons = analysis.path("persons");
        if (persons.isArray()) {
            List<String> personList = new ArrayList<>();
            for (JsonNode person : persons) {
                personList.add(person.asText());
            }
            result.setPersons(personList);
        }

        JsonNode place = analysis.path("place");
        if (!place.isNull() && !place.isMissingNode()) {
            String placeText = place.asText();
            if (!"null".equalsIgnoreCase(placeText) && !placeText.isEmpty()) {
                result.setPlace(placeText);
            }
        }

        JsonNode rating = analysis.path("rating");
        if (!rating.isNull() && !rating.isMissingNode()) {
            result.setRating(rating.asText());
        }
    }

    private String normalizeOpenAiBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    String extractJson(String text) {
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
     * Check if the current provider is configured (API key or Python availability).
     */
    public boolean isConfigured() {
        String provider = configService.getAiProvider();
        if ("moondream".equalsIgnoreCase(provider)) {
            return isMoondreamAvailable();
        }
        if ("ollama".equalsIgnoreCase(provider)) {
            return !configService.getOllamaBaseUrl().trim().isEmpty() &&
                    !configService.getOllamaModel().trim().isEmpty();
        }
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
        } else if ("ollama".equalsIgnoreCase(provider)) {
            return "Ollama";
        } else if ("moondream".equalsIgnoreCase(provider)) {
            return "Moondream (Local)";
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
            } else if ("ollama".equalsIgnoreCase(provider)) {
                model = configService.getOllamaModel();
            } else if ("moondream".equalsIgnoreCase(provider)) {
                model = configService.getMoondreamModel();
            } else {
                model = configService.getClaudeModel();
            }
            String prompt = configService.getClaudeAnalysisPrompt();

            // Normalize path for consistent hashing (Windows filesystems are case-insensitive,
            // so file walker may return different casing between runs)
            String normalizedPath = path.toAbsolutePath().normalize().toString();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                normalizedPath = normalizedPath.toLowerCase();
            }

            // Combine: provider + model + prompt + image path + file size + modification time
            String hashInput = provider + "|" + model + "|" + prompt + "|" + normalizedPath + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();

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

    /**
     * Recompute the analysis hash for an image and write it to the sidecar.
     * Used by batch rename: the file's path is part of the hash input, so a
     * rename otherwise invalidates the cache even though the underlying image
     * is unchanged. Callers should only invoke this when the cache was valid
     * before the rename (i.e. {@link #isAnalysisCached} returned true on the
     * old path), so we don't promote a stale hash.
     */
    public void refreshAnalysisHash(String imagePath) {
        saveAnalysisCache(imagePath);
    }
}
