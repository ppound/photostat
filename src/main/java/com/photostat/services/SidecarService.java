package com.photostat.services;

import com.photostat.models.ImageMetadata;

import java.nio.file.Path;
import java.util.List;

/**
 * Service for managing sidecar files that store custom metadata.
 *
 * <p>PhotoStat supports two sidecar formats, selectable via
 * {@code config.sidecar.format}:
 * <ul>
 *   <li><b>{@code json}</b> — the original {@code .photostat.json} format.</li>
 *   <li><b>{@code xmp}</b> — standard {@code .xmp} files readable by Lightroom,
 *   Bridge, digiKam, ExifTool, and Windows/Mac tooling that recognizes XMP.</li>
 *   <li><b>{@code both}</b> — writes both formats so users can migrate gradually.</li>
 * </ul>
 *
 * <p>When {@code config.sidecar.read_both} is {@code true}, read operations
 * first try the configured primary format and fall back to the other format
 * if no file is found. This enables seamless reads of either format during
 * a migration without forcing a one-time rewrite.
 *
 * <p>This class owns the merge logic (preserving {@code analysisHash} and
 * {@code cloudUploads} across partial writes, and deleting empty sidecars) so
 * the individual {@link SidecarBackend} implementations can stay simple.
 */
public class SidecarService {

    private static SidecarService instance;
    private final LoggingService logger;
    private final ConfigService configService;
    private final JsonSidecarBackend jsonBackend;
    private final XmpSidecarBackend xmpBackend;

    private SidecarService() {
        this.logger = LoggingService.getInstance();
        this.configService = ConfigService.getInstance();
        this.jsonBackend = new JsonSidecarBackend();
        this.xmpBackend = new XmpSidecarBackend();
    }

    public static synchronized SidecarService getInstance() {
        if (instance == null) {
            instance = new SidecarService();
        }
        return instance;
    }

    // --- Backend selection --------------------------------------------------

    /**
     * The backend for the user's currently configured primary format.
     * Used for most read/write operations.
     */
    private SidecarBackend primaryBackend() {
        return backendFor(configService.getSidecarFormat());
    }

    /**
     * The secondary backend (the "other" format), used for read fallback when
     * {@code read_both} is enabled, and for writes when format is {@code both}.
     */
    private SidecarBackend secondaryBackend() {
        String format = configService.getSidecarFormat();
        // "both" writes both; otherwise secondary is whichever is not primary
        if ("xmp".equalsIgnoreCase(format)) {
            return jsonBackend;
        }
        return xmpBackend;
    }

    private SidecarBackend backendFor(String format) {
        if ("xmp".equalsIgnoreCase(format)) {
            return xmpBackend;
        }
        // default: json (also covers "json" and "both" — "both" uses primary=json)
        return jsonBackend;
    }

    private boolean isBothMode() {
        return "both".equalsIgnoreCase(configService.getSidecarFormat());
    }

    // --- Public API: path / existence ---------------------------------------

    /**
     * Get the sidecar file path for an image in the currently-configured primary format.
     */
    public Path getSidecarPath(String imagePath) {
        return primaryBackend().getSidecarPath(imagePath);
    }

    /**
     * Check if a sidecar file exists for an image. Checks the primary format
     * first, then falls back to the secondary format when {@code read_both} is
     * enabled.
     */
    public boolean sidecarExists(String imagePath) {
        if (primaryBackend().exists(imagePath)) {
            return true;
        }
        if (configService.isSidecarReadBoth() && secondaryBackend().exists(imagePath)) {
            return true;
        }
        return false;
    }

    // --- Public API: read ---------------------------------------------------

    /**
     * Read custom metadata from a sidecar file. Tries the primary format first,
     * then falls back to the secondary format when {@code read_both} is enabled.
     *
     * @return the sidecar data, or {@code null} if nothing can be read.
     */
    public SidecarData readSidecar(String imagePath) {
        SidecarData data = primaryBackend().read(imagePath);
        if (data == null && configService.isSidecarReadBoth()) {
            data = secondaryBackend().read(imagePath);
        }
        return data;
    }

    /**
     * Read for internal use: returns existing primary-format data if any,
     * otherwise an empty {@link SidecarData} so callers can merge new values in.
     */
    private SidecarData readOrEmpty(String imagePath) {
        SidecarData existing = readSidecar(imagePath);
        return existing != null ? existing : new SidecarData();
    }

    // --- Public API: write --------------------------------------------------

    /**
     * Write custom metadata to a sidecar file, preserving any existing
     * {@code analysisHash} / {@code cloudUploads} fields. If all fields would
     * be empty after the write, the sidecar file(s) are deleted.
     */
    public boolean writeSidecar(String imagePath, List<String> persons, String place,
                                List<String> tags, String rating) {
        SidecarData merged = readOrEmpty(imagePath);
        merged.setPersons(persons);
        merged.setPlace(place);
        merged.setTags(tags);
        merged.setRating(rating);
        // analysisHash / cloudUploads preserved from readOrEmpty

        return persistMerged(imagePath, merged);
    }

    /**
     * Write custom metadata from an {@link ImageMetadata} object to a sidecar file.
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
     * Update the analysis hash in a sidecar file, preserving all other data.
     */
    public boolean updateAnalysisCache(String imagePath, String analysisHash) {
        SidecarData merged = readOrEmpty(imagePath);
        merged.setAnalysisHash(analysisHash);
        return persistMerged(imagePath, merged);
    }

    /**
     * Add a cloud upload record to a sidecar file. Preserves all existing data.
     */
    public boolean addCloudUpload(String imagePath, String remoteName) {
        SidecarData merged = readOrEmpty(imagePath);
        List<String> uploads = merged.getCloudUploads();
        if (uploads == null) {
            uploads = new java.util.ArrayList<>();
        } else {
            uploads = new java.util.ArrayList<>(uploads);
        }
        if (!uploads.contains(remoteName)) {
            uploads.add(remoteName);
        }
        merged.setCloudUploads(uploads);
        return persistMerged(imagePath, merged);
    }

    /**
     * Persist merged data to the appropriate backend(s). If the merged data is
     * fully empty (no custom metadata, no analysis hash, no cloud uploads),
     * deletes all sidecar files instead.
     */
    private boolean persistMerged(String imagePath, SidecarData merged) {
        boolean hasAnything = !merged.isEmpty()
                || merged.hasAnalysisCache()
                || (merged.getCloudUploads() != null && !merged.getCloudUploads().isEmpty());

        if (!hasAnything) {
            // Delete both backends so we don't leave stale data from either format
            boolean ok1 = jsonBackend.delete(imagePath);
            boolean ok2 = xmpBackend.delete(imagePath);
            return ok1 && ok2;
        }

        boolean primaryOk = primaryBackend().write(imagePath, merged);
        if (isBothMode()) {
            boolean secondaryOk = secondaryBackend().write(imagePath, merged);
            return primaryOk && secondaryOk;
        }
        return primaryOk;
    }

    /**
     * Apply sidecar data to an {@link ImageMetadata} object, populating its
     * custom metadata fields from whichever sidecar format is available.
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
     * Get analysis cache data from a sidecar file.
     * Returns null if no cache exists.
     */
    public SidecarData getAnalysisCache(String imagePath) {
        return readSidecar(imagePath);
    }

    /**
     * Delete a sidecar file. Deletes both JSON and XMP variants so the image
     * is left with no sidecar regardless of which format was in use.
     */
    public boolean deleteSidecar(String imagePath) {
        boolean ok1 = jsonBackend.delete(imagePath);
        boolean ok2 = xmpBackend.delete(imagePath);
        return ok1 && ok2;
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

        // Cloud upload tracking - list of remote names this file has been uploaded to
        private List<String> cloudUploads;

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

        public List<String> getCloudUploads() {
            return cloudUploads;
        }

        public void setCloudUploads(List<String> cloudUploads) {
            this.cloudUploads = cloudUploads;
        }

        public boolean isUploadedTo(String remoteName) {
            return cloudUploads != null && cloudUploads.contains(remoteName);
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
