package com.photostat.services;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Service for computing content and perceptual hashes of image files.
 * Content hash (SHA-256) detects exact byte-for-byte duplicates.
 * Perceptual hash (dHash) detects visually similar images across resizes and recompressions.
 */
public class HashService {

    private static HashService instance;
    private final LoggingService logger;

    private static final Set<String> RAW_EXTENSIONS = Set.of(
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf",
            ".pef", ".srw", ".x3f", ".raw", ".rwl"
    );

    private HashService() {
        this.logger = LoggingService.getInstance();
    }

    public static synchronized HashService getInstance() {
        if (instance == null) {
            instance = new HashService();
        }
        return instance;
    }

    /**
     * Compute SHA-256 hash of file contents.
     * Returns hex string or null on error.
     */
    public String computeContentHash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            logger.error("HashService", "Failed to compute content hash for " + file, e);
            return null;
        }
    }

    /**
     * Compute perceptual hash (dHash) of an image.
     * Resizes to 9x8, converts to grayscale, compares adjacent pixels to produce a 64-bit hash.
     * Returns 16-char hex string or null on error.
     */
    public String computePerceptualHash(Path file) {
        try {
            Image image;
            String ext = getFileExtension(file.getFileName().toString()).toLowerCase();

            if (RAW_EXTENSIONS.contains(ext)) {
                // For RAW files, use ThumbnailService to get a rendered preview
                ThumbnailService thumbnailService = ThumbnailService.getInstance();
                image = thumbnailService.getThumbnail(file.toAbsolutePath().toString());
                if (image == null) {
                    logger.warn("HashService", "Could not get thumbnail for RAW file: " + file);
                    return null;
                }
                // Re-render at 9x8 for hashing
                image = new Image(image.getUrl() != null ? image.getUrl() :
                        file.toUri().toString(), 9, 8, false, true);
                if (image.isError()) {
                    // Fallback: just use the thumbnail as-is and read pixels directly
                    image = thumbnailService.getThumbnail(file.toAbsolutePath().toString());
                    if (image == null) return null;
                    return computeDHashFromImage(image);
                }
            } else {
                // Load image at 9x8 resolution for dHash
                image = new Image(file.toUri().toString(), 9, 8, false, true);
                if (image.isError()) {
                    logger.warn("HashService", "Failed to load image for perceptual hash: " + file);
                    return null;
                }
            }

            return computeDHashFromImage(image);
        } catch (Exception e) {
            logger.error("HashService", "Failed to compute perceptual hash for " + file, e);
            return null;
        }
    }

    /**
     * Compute dHash from a loaded JavaFX Image.
     * If the image is larger than 9x8, it reads a 9x8 grid of sampled pixels.
     */
    private String computeDHashFromImage(Image image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();

        if (width < 2 || height < 1) {
            return null;
        }

        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return null;
        }

        // If image is exactly 9x8, use it directly
        // Otherwise, sample pixels at evenly spaced positions
        int hashWidth = Math.min(9, width);
        int hashHeight = Math.min(8, height);

        // Get grayscale brightness values
        double[][] brightness = new double[hashHeight][hashWidth];
        for (int y = 0; y < hashHeight; y++) {
            int srcY = (height > hashHeight) ? (int) ((double) y / hashHeight * height) : y;
            srcY = Math.min(srcY, height - 1);
            for (int x = 0; x < hashWidth; x++) {
                int srcX = (width > hashWidth) ? (int) ((double) x / hashWidth * width) : x;
                srcX = Math.min(srcX, width - 1);
                Color color = reader.getColor(srcX, srcY);
                // Convert to grayscale using luminance formula
                brightness[y][x] = 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue();
            }
        }

        // Compare each pixel to its right neighbor to produce 64 bits
        // (8 rows x 8 comparisons per row = 64 bits)
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < hashHeight; y++) {
            for (int x = 0; x < hashWidth - 1; x++) {
                if (bit >= 64) break;
                if (brightness[y][x] < brightness[y][x + 1]) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }

        return String.format("%016x", hash);
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
