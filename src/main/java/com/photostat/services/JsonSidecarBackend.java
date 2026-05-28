package com.photostat.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sidecar backend that stores metadata in {@code .photostat.json} files.
 * This is the original PhotoStat sidecar format.
 */
class JsonSidecarBackend implements SidecarBackend {

    static final String SIDECAR_EXTENSION = ".photostat.json";

    private final ObjectMapper objectMapper;
    private final LoggingService logger;

    JsonSidecarBackend() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.logger = LoggingService.getInstance();
    }

    @Override
    public Path getSidecarPath(String imagePath) {
        return Path.of(imagePath + SIDECAR_EXTENSION);
    }

    @Override
    public boolean exists(String imagePath) {
        return Files.exists(getSidecarPath(imagePath));
    }

    @Override
    @SuppressWarnings("unchecked")
    public SidecarService.SidecarData read(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        if (!Files.exists(sidecarPath)) {
            return null;
        }

        try {
            Map<String, Object> data = objectMapper.readValue(sidecarPath.toFile(), Map.class);
            SidecarService.SidecarData sidecar = new SidecarService.SidecarData();

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
            if (data.containsKey("analysisHash")) {
                sidecar.setAnalysisHash((String) data.get("analysisHash"));
            }
            if (data.containsKey("cloudUploads")) {
                sidecar.setCloudUploads((List<String>) data.get("cloudUploads"));
            }
            if (data.containsKey("previousFilenames")) {
                sidecar.setPreviousFilenames((List<String>) data.get("previousFilenames"));
            }

            logger.debug("JsonSidecarBackend", "Read sidecar for: " + imagePath);
            return sidecar;

        } catch (IOException e) {
            logger.error("JsonSidecarBackend", "Failed to read sidecar: " + sidecarPath, e);
            return null;
        }
    }

    @Override
    public boolean write(String imagePath, SidecarService.SidecarData data) {
        Path sidecarPath = getSidecarPath(imagePath);
        Map<String, Object> map = new HashMap<>();

        if (data.getPersons() != null && !data.getPersons().isEmpty()) {
            map.put("persons", data.getPersons());
        }
        if (data.getPlace() != null && !data.getPlace().trim().isEmpty()) {
            map.put("place", data.getPlace().trim());
        }
        if (data.getTags() != null && !data.getTags().isEmpty()) {
            map.put("tags", data.getTags());
        }
        if (data.getRating() != null && !data.getRating().trim().isEmpty()) {
            map.put("rating", data.getRating().trim());
        }
        if (data.getAnalysisHash() != null && !data.getAnalysisHash().isEmpty()) {
            map.put("analysisHash", data.getAnalysisHash());
        }
        if (data.getCloudUploads() != null && !data.getCloudUploads().isEmpty()) {
            map.put("cloudUploads", data.getCloudUploads());
        }
        if (data.getPreviousFilenames() != null && !data.getPreviousFilenames().isEmpty()) {
            map.put("previousFilenames", data.getPreviousFilenames());
        }

        try {
            objectMapper.writeValue(sidecarPath.toFile(), map);
            logger.info("JsonSidecarBackend", "Wrote sidecar: " + sidecarPath);
            return true;
        } catch (IOException e) {
            logger.error("JsonSidecarBackend", "Failed to write sidecar: " + sidecarPath, e);
            return false;
        }
    }

    @Override
    public boolean delete(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        try {
            if (Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                logger.info("JsonSidecarBackend", "Deleted sidecar: " + sidecarPath);
            }
            return true;
        } catch (IOException e) {
            logger.error("JsonSidecarBackend", "Failed to delete sidecar: " + sidecarPath, e);
            return false;
        }
    }
}
