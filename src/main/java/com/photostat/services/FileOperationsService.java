package com.photostat.services;

import com.photostat.models.ImageMetadata;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for file operations like copying and deleting images.
 * Handles sidecar files (both JSON and XMP backends) alongside images.
 */
public class FileOperationsService {

    private static FileOperationsService instance;
    private final ConfigService configService;
    private final LoggingService logger;
    private final SidecarService sidecarService;

    private FileOperationsService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
        this.sidecarService = SidecarService.getInstance();
    }

    public static synchronized FileOperationsService getInstance() {
        if (instance == null) {
            instance = new FileOperationsService();
        }
        return instance;
    }

    /**
     * Copy a single image to a destination directory.
     * Also copies the sidecar file if it exists.
     *
     * @param imagePath Source image path
     * @param destinationDir Destination directory
     * @param overwrite Whether to overwrite existing files
     * @return Result of the operation
     */
    public OperationResult copyImage(String imagePath, Path destinationDir, boolean overwrite) {
        try {
            Path source = Path.of(imagePath);
            if (!Files.exists(source)) {
                return new OperationResult(false, "Source file not found: " + imagePath);
            }

            if (!Files.exists(destinationDir)) {
                Files.createDirectories(destinationDir);
            }

            Path destination = destinationDir.resolve(source.getFileName());

            // Check if destination exists
            if (Files.exists(destination) && !overwrite) {
                return new OperationResult(false, "Destination file already exists: " + destination);
            }

            // Copy the image
            CopyOption[] options = overwrite
                ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES}
                : new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};

            Files.copy(source, destination, options);
            logger.info("FileOperationsService", "Copied: " + source + " -> " + destination);

            // Copy all sidecar files (JSON and/or XMP) that travel with this image.
            for (Path sidecarSource : sidecarService.getAllExistingSidecarPaths(imagePath)) {
                Path sidecarDest = destinationDir.resolve(
                        relocatedSidecarFilename(source, sidecarSource, source.getFileName().toString()));
                Files.copy(sidecarSource, sidecarDest, options);
                logger.info("FileOperationsService", "Copied sidecar: " + sidecarSource + " -> " + sidecarDest);
            }

            return new OperationResult(true, "Successfully copied: " + source.getFileName());

        } catch (IOException e) {
            logger.error("FileOperationsService", "Failed to copy: " + imagePath, e);
            return new OperationResult(false, "Failed to copy: " + e.getMessage());
        }
    }

    /**
     * Copy multiple images to a destination directory.
     *
     * @param imagePaths List of source image paths
     * @param destinationDir Destination directory
     * @param overwrite Whether to overwrite existing files
     * @return Batch result of the operations
     */
    public BatchOperationResult copyImages(List<String> imagePaths, Path destinationDir, boolean overwrite) {
        BatchOperationResult result = new BatchOperationResult();

        for (String imagePath : imagePaths) {
            OperationResult opResult = copyImage(imagePath, destinationDir, overwrite);
            if (opResult.isSuccess()) {
                result.successCount++;
            } else {
                result.failureCount++;
                result.errors.add(opResult.getMessage());
            }
        }

        return result;
    }

    /**
     * Delete a single image.
     * Also deletes the sidecar file if it exists.
     *
     * @param imagePath Image path to delete
     * @param deleteSidecar Whether to also delete the sidecar file
     * @return Result of the operation
     */
    public OperationResult deleteImage(String imagePath, boolean deleteSidecar) {
        try {
            Path source = Path.of(imagePath);
            if (!Files.exists(source)) {
                return new OperationResult(false, "File not found: " + imagePath);
            }

            // Delete the image
            Files.delete(source);
            logger.info("FileOperationsService", "Deleted: " + source);

            // Delete all sidecar files if requested
            if (deleteSidecar) {
                for (Path sidecarPath : sidecarService.getAllExistingSidecarPaths(imagePath)) {
                    Files.delete(sidecarPath);
                    logger.info("FileOperationsService", "Deleted sidecar: " + sidecarPath);
                }
            }

            return new OperationResult(true, "Successfully deleted: " + source.getFileName());

        } catch (IOException e) {
            logger.error("FileOperationsService", "Failed to delete: " + imagePath, e);
            return new OperationResult(false, "Failed to delete: " + e.getMessage());
        }
    }

    /**
     * Delete multiple images.
     *
     * @param imagePaths List of image paths to delete
     * @param deleteSidecar Whether to also delete sidecar files
     * @return Batch result of the operations
     */
    public BatchOperationResult deleteImages(List<String> imagePaths, boolean deleteSidecar) {
        BatchOperationResult result = new BatchOperationResult();

        for (String imagePath : imagePaths) {
            OperationResult opResult = deleteImage(imagePath, deleteSidecar);
            if (opResult.isSuccess()) {
                result.successCount++;
            } else {
                result.failureCount++;
                result.errors.add(opResult.getMessage());
            }
        }

        return result;
    }

    /**
     * Move a single image to a destination directory (copy + delete).
     *
     * @param imagePath Source image path
     * @param destinationDir Destination directory
     * @param overwrite Whether to overwrite existing files
     * @return Result of the operation
     */
    public OperationResult moveImage(String imagePath, Path destinationDir, boolean overwrite) {
        try {
            Path source = Path.of(imagePath);
            if (!Files.exists(source)) {
                return new OperationResult(false, "Source file not found: " + imagePath);
            }

            if (!Files.exists(destinationDir)) {
                Files.createDirectories(destinationDir);
            }

            Path destination = destinationDir.resolve(source.getFileName());

            // Check if destination exists
            if (Files.exists(destination) && !overwrite) {
                return new OperationResult(false, "Destination file already exists: " + destination);
            }

            // Move the image
            CopyOption[] options = overwrite
                ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new CopyOption[]{};

            Files.move(source, destination, options);
            logger.info("FileOperationsService", "Moved: " + source + " -> " + destination);

            // Move all sidecar files (JSON and/or XMP) that travel with this image.
            for (Path sidecarSource : sidecarService.getAllExistingSidecarPaths(imagePath)) {
                Path sidecarDest = destinationDir.resolve(
                        relocatedSidecarFilename(source, sidecarSource, source.getFileName().toString()));
                Files.move(sidecarSource, sidecarDest, options);
                logger.info("FileOperationsService", "Moved sidecar: " + sidecarSource + " -> " + sidecarDest);
            }

            return new OperationResult(true, "Successfully moved: " + source.getFileName());

        } catch (IOException e) {
            logger.error("FileOperationsService", "Failed to move: " + imagePath, e);
            return new OperationResult(false, "Failed to move: " + e.getMessage());
        }
    }

    /**
     * Move multiple images to a destination directory.
     *
     * @param imagePaths List of source image paths
     * @param destinationDir Destination directory
     * @param overwrite Whether to overwrite existing files
     * @return Batch result of the operations
     */
    public BatchOperationResult moveImages(List<String> imagePaths, Path destinationDir, boolean overwrite) {
        BatchOperationResult result = new BatchOperationResult();

        for (String imagePath : imagePaths) {
            OperationResult opResult = moveImage(imagePath, destinationDir, overwrite);
            if (opResult.isSuccess()) {
                result.successCount++;
            } else {
                result.failureCount++;
                result.errors.add(opResult.getMessage());
            }
        }

        return result;
    }

    /**
     * Rename a single image in place (same directory, new filename). Renames
     * all sidecars (JSON and XMP) that exist alongside the image, and records
     * the old basename in the JSON sidecar's {@code previousFilenames} history
     * (creating the sidecar if absent) so sibling files like RAW originals can
     * still be located later.
     *
     * @param imagePath Current image path
     * @param newFilename New basename (filename only, not a path)
     * @return Result of the operation; the message contains the new full path on success
     */
    public OperationResult renameImage(String imagePath, String newFilename) {
        try {
            Path source = Path.of(imagePath);
            if (!Files.exists(source)) {
                return new OperationResult(false, "Source file not found: " + imagePath);
            }

            String oldFilename = source.getFileName().toString();
            if (newFilename == null || newFilename.isEmpty() || newFilename.equals(oldFilename)) {
                return new OperationResult(false, "New filename is empty or unchanged: " + oldFilename);
            }
            if (newFilename.contains("/") || newFilename.contains("\\")) {
                return new OperationResult(false, "New filename must not contain path separators: " + newFilename);
            }

            Path parent = source.getParent();
            Path destination = parent.resolve(newFilename);

            if (Files.exists(destination)) {
                return new OperationResult(false, "Destination file already exists: " + destination);
            }

            // Record original basename before renaming sidecars — this writes
            // to the sidecar at the OLD image path so it moves with the rename.
            sidecarService.appendPreviousFilename(imagePath, oldFilename);

            Files.move(source, destination);
            logger.info("FileOperationsService", "Renamed: " + source + " -> " + destination);

            // Rename all sidecar files alongside the image.
            for (Path sidecarSource : sidecarService.getAllExistingSidecarPaths(imagePath)) {
                Path sidecarDest = parent.resolve(
                        relocatedSidecarFilename(source, sidecarSource, newFilename));
                Files.move(sidecarSource, sidecarDest);
                logger.info("FileOperationsService", "Renamed sidecar: " + sidecarSource + " -> " + sidecarDest);
            }

            return new OperationResult(true, destination.toString());

        } catch (IOException e) {
            logger.error("FileOperationsService", "Failed to rename: " + imagePath, e);
            return new OperationResult(false, "Failed to rename: " + e.getMessage());
        }
    }

    /**
     * Rename multiple images in place. Skips entries with empty/unchanged names
     * and reports per-file errors without aborting the batch.
     *
     * @param renames Map of current image path to new basename
     * @return Batch result with per-file success/failure
     */
    public BatchOperationResult renameImages(Map<String, String> renames) {
        BatchOperationResult result = new BatchOperationResult();
        for (Map.Entry<String, String> entry : renames.entrySet()) {
            OperationResult opResult = renameImage(entry.getKey(), entry.getValue());
            if (opResult.isSuccess()) {
                result.successCount++;
            } else {
                result.failureCount++;
                result.errors.add(opResult.getMessage());
            }
        }
        return result;
    }

    /**
     * Compute the destination filename for a sidecar travelling alongside an
     * image, preserving the sidecar's suffix (e.g. {@code .photostat.json} or
     * {@code .xmp}). Works for both image rename (new basename) and move/copy
     * (same basename).
     */
    private static String relocatedSidecarFilename(Path imageSource, Path sidecarSource, String newImageFilename) {
        String oldImageFilename = imageSource.getFileName().toString();
        String sidecarFilename = sidecarSource.getFileName().toString();
        String suffix = sidecarFilename.startsWith(oldImageFilename)
                ? sidecarFilename.substring(oldImageFilename.length())
                : sidecarFilename;
        return newImageFilename + suffix;
    }

    /**
     * Result of a single file operation.
     */
    public static class OperationResult {
        private final boolean success;
        private final String message;

        public OperationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Result of a batch file operation.
     */
    public static class BatchOperationResult {
        public int successCount = 0;
        public int failureCount = 0;
        public List<String> errors = new ArrayList<>();

        public boolean hasErrors() {
            return failureCount > 0;
        }

        public int getTotalCount() {
            return successCount + failureCount;
        }

        public String getSummary() {
            if (failureCount == 0) {
                return "Successfully processed " + successCount + " file(s).";
            } else {
                return "Processed " + successCount + " file(s), " + failureCount + " error(s).";
            }
        }
    }
}
