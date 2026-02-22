package com.photostat.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.photostat.models.ImageMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing sidecar files that store custom metadata.
 * Sidecar files are JSON files stored alongside images with the extension .photostat.json
 * They contain user-defined metadata like persons, place, and tags.
 */
public class SidecarService {

    private static SidecarService instance;
    private final LoggingService logger;
    private final ObjectMapper objectMapper;

    private static final String SIDECAR_EXTENSION = ".photostat.json";

    private SidecarService() {
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static synchronized SidecarService getInstance() {
        if (instance == null) {
            instance = new SidecarService();
        }
        return instance;
    }

    /**
     * Get the sidecar file path for an image.
     */
    public Path getSidecarPath(String imagePath) {
        return Path.of(imagePath + SIDECAR_EXTENSION);
    }

    /**
     * Check if a sidecar file exists for an image.
     */
    public boolean sidecarExists(String imagePath) {
        return Files.exists(getSidecarPath(imagePath));
    }

    /**
     * Read custom metadata from a sidecar file.
     * Returns null if sidecar doesn't exist or can't be read.
     */
    @SuppressWarnings("unchecked")
    public SidecarData readSidecar(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        if (!Files.exists(sidecarPath)) {
            return null;
        }

        try {
            Map<String, Object> data = objectMapper.readValue(sidecarPath.toFile(), Map.class);
            SidecarData sidecar = new SidecarData();

            if (data.containsKey("persons")) {
                sidecar.setPersons((List<String>) data.get("persons"));
            }
            if (data.containsKey("place")) {
                sidecar.setPlace((String) data.get("place"));
            }
            if (data.containsKey("tags")) {
                sidecar.setTags((List<String>) data.get("tags"));
            }
            if (data.containsKey("rating")) {
                sidecar.setRating((String) data.get("rating"));
            }

            // Analysis cache field
            if (data.containsKey("analysisHash")) {
                sidecar.setAnalysisHash((String) data.get("analysisHash"));
            }

            logger.debug("SidecarService", "Read sidecar for: " + imagePath);
            return sidecar;

        } catch (IOException e) {
            logger.error("SidecarService", "Failed to read sidecar: " + sidecarPath, e);
            return null;
        }
    }

    /**
     * Write custom metadata to a sidecar file.
     * Preserves existing analysisHash if present.
     */
    @SuppressWarnings("unchecked")
    public boolean writeSidecar(String imagePath, List<String> persons, String place, List<String> tags, String rating) {
        // Check if there's any custom metadata to write
        boolean hasPersons = persons != null && !persons.isEmpty();
        boolean hasPlace = place != null && !place.trim().isEmpty();
        boolean hasTags = tags != null && !tags.isEmpty();
        boolean hasRating = rating != null && !rating.trim().isEmpty();

        Path sidecarPath = getSidecarPath(imagePath);

        // Read existing sidecar to preserve analysisHash
        String existingAnalysisHash = null;
        if (Files.exists(sidecarPath)) {
            try {
                Map<String, Object> existingData = objectMapper.readValue(sidecarPath.toFile(), Map.class);
                existingAnalysisHash = (String) existingData.get("analysisHash");
            } catch (IOException e) {
                logger.warn("SidecarService", "Failed to read existing sidecar for preservation: " + sidecarPath);
            }
        }

        // If no custom metadata and no analysisHash, delete sidecar if it exists
        if (!hasPersons && !hasPlace && !hasTags && !hasRating && existingAnalysisHash == null) {
            try {
                if (Files.exists(sidecarPath)) {
                    Files.delete(sidecarPath);
                    logger.debug("SidecarService", "Deleted empty sidecar: " + sidecarPath);
                }
                return true;
            } catch (IOException e) {
                logger.error("SidecarService", "Failed to delete sidecar: " + sidecarPath, e);
                return false;
            }
        }

        // Build sidecar data
        Map<String, Object> data = new HashMap<>();
        if (hasPersons) {
            data.put("persons", persons);
        }
        if (hasPlace) {
            data.put("place", place.trim());
        }
        if (hasTags) {
            data.put("tags", tags);
        }
        if (hasRating) {
            data.put("rating", rating.trim());
        }

        // Preserve existing analysisHash
        if (existingAnalysisHash != null) {
            data.put("analysisHash", existingAnalysisHash);
        }

        try {
            objectMapper.writeValue(sidecarPath.toFile(), data);
            logger.info("SidecarService", "Wrote sidecar: " + sidecarPath);
            return true;
        } catch (IOException e) {
            logger.error("SidecarService", "Failed to write sidecar: " + sidecarPath, e);
            return false;
        }
    }

    /**
     * Write custom metadata from an ImageMetadata object to a sidecar file.
     */
    public boolean writeSidecar(ImageMetadata metadata) {
        return writeSidecar(
                metadata.getFilePath(),
                metadata.getPersons(),
                metadata.getPlace(),
                metadata.getTags(),
                metadata.getRating()
        );
    }

    /**
     * Apply sidecar data to an ImageMetadata object.
     */
    public void applySidecarToMetadata(ImageMetadata metadata) {
        SidecarData sidecar = readSidecar(metadata.getFilePath());
        if (sidecar != null) {
            if (sidecar.getPersons() != null && !sidecar.getPersons().isEmpty()) {
                metadata.setPersons(sidecar.getPersons());
            }
            if (sidecar.getPlace() != null && !sidecar.getPlace().isEmpty()) {
                metadata.setPlace(sidecar.getPlace());
            }
            if (sidecar.getTags() != null && !sidecar.getTags().isEmpty()) {
                metadata.setTags(sidecar.getTags());
            }
            if (sidecar.getRating() != null && !sidecar.getRating().isEmpty()) {
                metadata.setRating(sidecar.getRating());
            }
            logger.debug("SidecarService", "Applied sidecar data to: " + metadata.getFilePath());
        }
    }

    /**
     * Update the analysis hash in a sidecar file.
     * Preserves existing custom metadata while updating cache field.
     */
    public boolean updateAnalysisCache(String imagePath, String analysisHash) {
        Path sidecarPath = getSidecarPath(imagePath);
        Map<String, Object> data = new HashMap<>();

        // Read existing data if present
        if (Files.exists(sidecarPath)) {
            try {
                data = objectMapper.readValue(sidecarPath.toFile(), Map.class);
            } catch (IOException e) {
                logger.warn("SidecarService", "Failed to read existing sidecar, creating new: " + sidecarPath);
                data = new HashMap<>();
            }
        }

        // Update analysis hash
        data.put("analysisHash", analysisHash);

        try {
            objectMapper.writeValue(sidecarPath.toFile(), data);
            logger.debug("SidecarService", "Updated analysis cache in sidecar: " + sidecarPath);
            return true;
        } catch (IOException e) {
            logger.error("SidecarService", "Failed to update analysis cache in sidecar: " + sidecarPath, e);
            return false;
        }
    }

    /**
     * Get analysis cache data from a sidecar file.
     * Returns null if no cache exists.
     */
    public SidecarData getAnalysisCache(String imagePath) {
        return readSidecar(imagePath);
    }

    /**
     * Delete a sidecar file.
     */
    public boolean deleteSidecar(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        try {
            if (Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                logger.info("SidecarService", "Deleted sidecar: " + sidecarPath);
            }
            return true;
        } catch (IOException e) {
            logger.error("SidecarService", "Failed to delete sidecar: " + sidecarPath, e);
            return false;
        }
    }

    /**
     * Data class for sidecar content.
     */
    public static class SidecarData {
        private List<String> persons;
        private String place;
        private List<String> tags;
        private String rating;

        // Analysis cache field - combined hash of model + prompt + image (size + modification time)
        private String analysisHash;

        public List<String> getPersons() {
            return persons;
        }

        public void setPersons(List<String> persons) {
            this.persons = persons;
        }

        public String getPlace() {
            return place;
        }

        public void setPlace(String place) {
            this.place = place;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public String getRating() {
            return rating;
        }

        public void setRating(String rating) {
            this.rating = rating;
        }

        public String getAnalysisHash() {
            return analysisHash;
        }

        public void setAnalysisHash(String analysisHash) {
            this.analysisHash = analysisHash;
        }

        public boolean isEmpty() {
            boolean noPersons = persons == null || persons.isEmpty();
            boolean noPlace = place == null || place.trim().isEmpty();
            boolean noTags = tags == null || tags.isEmpty();
            boolean noRating = rating == null || rating.trim().isEmpty();
            return noPersons && noPlace && noTags && noRating;
        }

        public boolean hasAnalysisCache() {
            return analysisHash != null && !analysisHash.isEmpty();
        }
    }
}
