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

    /**
     * Return the on-disk paths of all sidecar files that currently exist for
     * the given image, across both JSON and XMP backends. Used by file
     * operations (move, copy, rename) so every sidecar travels with the image
     * regardless of which format the user has configured.
     */
    public List<Path> getAllExistingSidecarPaths(String imagePath) {
        List<Path> paths = new java.util.ArrayList<>(2);
        if (jsonBackend.exists(imagePath)) {
            paths.add(jsonBackend.getSidecarPath(imagePath));
        }
        if (xmpBackend.exists(imagePath)) {
            paths.add(xmpBackend.getSidecarPath(imagePath));
        }
        return paths;
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
     *
     * <p>{@code previousFilenames} is JSON-only, so if the primary read came
     * from XMP we still pull the history from the JSON sidecar to preserve it
     * across writes.
     */
    private SidecarData readOrEmpty(String imagePath) {
        SidecarData existing = readSidecar(imagePath);
        if (existing == null) {
            existing = new SidecarData();
        }
        if (!existing.hasPreviousFilenames() && primaryBackend() != jsonBackend) {
            SidecarData json = jsonBackend.read(imagePath);
            if (json != null && json.hasPreviousFilenames()) {
                existing.setPreviousFilenames(json.getPreviousFilenames());
            }
        }
        return existing;
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
     * Append a basename to the rename history for an image, creating the JSON
     * sidecar if absent. Called by batch rename to record the original name so
     * sibling files (e.g. a matching RAW) can still be located later.
     */
    public boolean appendPreviousFilename(String imagePath, String oldBasename) {
        SidecarData merged = readOrEmpty(imagePath);
        List<String> history = merged.getPreviousFilenames();
        history = history == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(history);
        history.add(oldBasename);
        merged.setPreviousFilenames(history);
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
     * fully empty (no custom metadata, no analysis hash, no cloud uploads, no
     * rename history), deletes all sidecar files instead.
     *
     * <p>{@code previousFilenames} is JSON-only — when XMP is the primary
     * format we still write the JSON sidecar to preserve history.
     */
    private boolean persistMerged(String imagePath, SidecarData merged) {
        boolean hasUserContent = !merged.isEmpty()
                || merged.hasAnalysisCache()
                || (merged.getCloudUploads() != null && !merged.getCloudUploads().isEmpty());
        boolean hasHistory = merged.hasPreviousFilenames();

        if (!hasUserContent && !hasHistory) {
            // Delete both backends so we don't leave stale data from either format
            boolean ok1 = jsonBackend.delete(imagePath);
            boolean ok2 = xmpBackend.delete(imagePath);
            return ok1 && ok2;
        }

        boolean ok = true;
        if (hasUserContent) {
            ok &= primaryBackend().write(imagePath, merged);
            if (isBothMode()) {
                ok &= secondaryBackend().write(imagePath, merged);
            }
        }

        // Ensure JSON carries previousFilenames even when XMP is primary or no
        // user-content write happened above.
        boolean jsonAlreadyWritten = hasUserContent
                && (primaryBackend() == jsonBackend || isBothMode());
        if (hasHistory && !jsonAlreadyWritten) {
            ok &= jsonBackend.write(imagePath, merged);
        }
        return ok;
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
     * Outcome of a single {@link #convertJsonToXmp} call.
     */
    public enum ConversionResult {
        /** JSON sidecar was read and successfully written to XMP. */
        CONVERTED,
        /** No JSON sidecar exists for this image — nothing to convert. */
        SKIPPED_NO_JSON,
        /** JSON sidecar existed but held no usable data — nothing worth migrating. */
        SKIPPED_EMPTY,
        /** Read or write failed. See the log for details. */
        FAILED
    }

    /**
     * Convert a single image's {@code .photostat.json} sidecar to an {@code .xmp}
     * sidecar, preserving all fields (persons, place, tags, rating, analysisHash,
     * cloudUploads). If {@code deleteJsonAfter} is true, the original JSON
     * sidecar is deleted on success.
     *
     * <p>This method bypasses the configured primary format and always reads
     * from JSON / writes to XMP — it is intended for one-shot migration, not
     * general persistence. Use {@link #writeSidecar} for normal writes.
     *
     * @return the {@link ConversionResult} describing what happened.
     */
    public ConversionResult convertJsonToXmp(String imagePath, boolean deleteJsonAfter) {
        if (!jsonBackend.exists(imagePath)) {
            return ConversionResult.SKIPPED_NO_JSON;
        }

        SidecarData data = jsonBackend.read(imagePath);
        if (data == null) {
            // File exists but is unreadable — treat as failure so the user sees it.
            logger.warn("SidecarService", "Failed to parse JSON sidecar during conversion: " + imagePath);
            return ConversionResult.FAILED;
        }

        boolean hasAnything = !data.isEmpty()
                || data.hasAnalysisCache()
                || (data.getCloudUploads() != null && !data.getCloudUploads().isEmpty());
        if (!hasAnything) {
            // Empty sidecar — nothing to carry over. Still offer to delete it
            // since it's just noise on disk.
            if (deleteJsonAfter) {
                jsonBackend.delete(imagePath);
            }
            return ConversionResult.SKIPPED_EMPTY;
        }

        if (!xmpBackend.write(imagePath, data)) {
            return ConversionResult.FAILED;
        }

        if (deleteJsonAfter) {
            if (!jsonBackend.delete(imagePath)) {
                // XMP is already written, so treat delete-failure as a soft
                // problem: conversion succeeded, cleanup didn't.
                logger.warn("SidecarService",
                        "XMP written but failed to delete original JSON sidecar: " + imagePath);
            }
        }

        return ConversionResult.CONVERTED;
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

        // Rename history — basenames the file previously had, oldest first.
        // Useful for locating sibling RAW files after a batch rename. JSON-only;
        // XMP sidecars do not carry this field.
        private List<String> previousFilenames;

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

        public List<String> getPreviousFilenames() {
            return previousFilenames;
        }

        public void setPreviousFilenames(List<String> previousFilenames) {
            this.previousFilenames = previousFilenames;
        }

        public boolean hasPreviousFilenames() {
            return previousFilenames != null && !previousFilenames.isEmpty();
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
