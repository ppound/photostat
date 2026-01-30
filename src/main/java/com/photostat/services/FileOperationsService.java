package com.photostat.services;

import com.photostat.models.ImageMetadata;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for file operations like copying and deleting images.
 * Handles sidecar files alongside images when configured.
 */
public class FileOperationsService {

    private static FileOperationsService instance;
    private final ConfigService configService;
    private final LoggingService logger;

    private static final String SIDECAR_EXTENSION = ".photostat.json";

    private FileOperationsService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
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

            // Copy sidecar file if it exists
            Path sidecarSource = Path.of(imagePath + SIDECAR_EXTENSION);
            if (Files.exists(sidecarSource)) {
                Path sidecarDest = destinationDir.resolve(source.getFileName() + SIDECAR_EXTENSION);
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

            // Delete sidecar file if requested and exists
            if (deleteSidecar) {
                Path sidecarPath = Path.of(imagePath + SIDECAR_EXTENSION);
                if (Files.exists(sidecarPath)) {
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

            // Move sidecar file if it exists
            Path sidecarSource = Path.of(imagePath + SIDECAR_EXTENSION);
            if (Files.exists(sidecarSource)) {
                Path sidecarDest = destinationDir.resolve(source.getFileName() + SIDECAR_EXTENSION);
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
