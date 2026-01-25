package com.photostat.services;

import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for generating image thumbnails.
 */
public class ThumbnailService {

    private static ThumbnailService instance;
    private final ConfigService configService;

    // Simple in-memory cache for thumbnails
    private final Map<String, Image> thumbnailCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 500;

    // RAW file extensions that need ExifTool for thumbnail extraction
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf",
            ".pef", ".srw", ".x3f", ".raw", ".rwl", ".rw2"
    );

    private ThumbnailService() {
        this.configService = ConfigService.getInstance();
    }

    public static synchronized ThumbnailService getInstance() {
        if (instance == null) {
            instance = new ThumbnailService();
        }
        return instance;
    }

    /**
     * Get a thumbnail for an image file.
     */
    public Image getThumbnail(String filePath) {
        // Check cache first
        Image cached = thumbnailCache.get(filePath);
        if (cached != null) {
            return cached;
        }

        // Generate thumbnail
        Image thumbnail = generateThumbnail(filePath);
        if (thumbnail != null) {
            // Add to cache with size limit
            if (thumbnailCache.size() >= MAX_CACHE_SIZE) {
                // Simple eviction: remove a random entry
                String keyToRemove = thumbnailCache.keySet().iterator().next();
                thumbnailCache.remove(keyToRemove);
            }
            thumbnailCache.put(filePath, thumbnail);
        }

        return thumbnail;
    }

    /**
     * Generate a thumbnail for the given file.
     */
    private Image generateThumbnail(String filePath) {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            return null;
        }

        int maxSize = configService.getThumbnailSize();
        String extension = getFileExtension(filePath).toLowerCase();

        // Try ExifTool for RAW files
        if (RAW_EXTENSIONS.contains(extension)) {
            Image thumbnail = generateThumbnailWithExifTool(path, maxSize);
            if (thumbnail != null) {
                return thumbnail;
            }
        }

        // Use standard Java image loading
        return generateThumbnailWithImageIO(path, maxSize);
    }

    /**
     * Generate thumbnail using Java ImageIO.
     */
    private Image generateThumbnailWithImageIO(Path path, int maxSize) {
        try {
            // Use JavaFX Image with background loading and scaling
            Image image = new Image(
                    path.toUri().toString(),
                    maxSize,  // requested width
                    maxSize,  // requested height
                    true,     // preserve ratio
                    true,     // smooth
                    false     // background loading disabled for immediate result
            );

            if (!image.isError()) {
                return image;
            }
        } catch (Exception e) {
            System.err.println("JavaFX image loading failed: " + e.getMessage());
        }

        // Fallback to AWT/ImageIO
        try {
            BufferedImage originalImage = ImageIO.read(path.toFile());
            if (originalImage == null) {
                return null;
            }

            BufferedImage scaledImage = scaleImage(originalImage, maxSize);

            // Convert to JavaFX Image
            return convertToFxImage(scaledImage);

        } catch (Exception e) {
            System.err.println("ImageIO loading failed for " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Generate thumbnail using ExifTool (for RAW files with embedded previews).
     */
    private Image generateThumbnailWithExifTool(Path path, int maxSize) {
        String exifToolPath = configService.getExifToolPath();

        try {
            // Try to extract the embedded preview/thumbnail
            ProcessBuilder pb = new ProcessBuilder(
                    exifToolPath,
                    "-b",                    // Binary output
                    "-PreviewImage",         // Extract preview image
                    path.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            byte[] imageData;

            try (InputStream is = process.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                imageData = baos.toByteArray();
            }

            int exitCode = process.waitFor();

            // If PreviewImage failed, try JpgFromRaw
            if (exitCode != 0 || imageData.length < 100) {
                pb = new ProcessBuilder(
                        exifToolPath,
                        "-b",
                        "-JpgFromRaw",
                        path.toString()
                );
                process = pb.start();

                try (InputStream is = process.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageData = baos.toByteArray();
                }
                exitCode = process.waitFor();
            }

            // If still no luck, try ThumbnailImage
            if (exitCode != 0 || imageData.length < 100) {
                pb = new ProcessBuilder(
                        exifToolPath,
                        "-b",
                        "-ThumbnailImage",
                        path.toString()
                );
                process = pb.start();

                try (InputStream is = process.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageData = baos.toByteArray();
                }
                process.waitFor();
            }

            if (imageData.length > 100) {
                // Read the extracted image
                try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
                    BufferedImage originalImage = ImageIO.read(bais);
                    if (originalImage != null) {
                        BufferedImage scaledImage = scaleImage(originalImage, maxSize);
                        return convertToFxImage(scaledImage);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("ExifTool thumbnail extraction failed for " + path + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Scale a BufferedImage to fit within maxSize while preserving aspect ratio.
     */
    private BufferedImage scaleImage(BufferedImage original, int maxSize) {
        int width = original.getWidth();
        int height = original.getHeight();

        // Calculate scaling factor
        double scale = Math.min((double) maxSize / width, (double) maxSize / height);

        if (scale >= 1.0) {
            // Image is already smaller than maxSize
            return original;
        }

        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);

        // Create scaled image
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return scaled;
    }

    /**
     * Convert a BufferedImage to a JavaFX Image.
     */
    private Image convertToFxImage(BufferedImage bufferedImage) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", baos);
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            return new Image(bais);
        } catch (IOException e) {
            System.err.println("Failed to convert image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get a thumbnail asynchronously.
     */
    public void getThumbnailAsync(String filePath, ThumbnailCallback callback) {
        Thread thread = new Thread(() -> {
            Image thumbnail = getThumbnail(filePath);
            callback.onThumbnailReady(filePath, thumbnail);
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Clear the thumbnail cache.
     */
    public void clearCache() {
        thumbnailCache.clear();
    }

    /**
     * Remove a specific thumbnail from cache.
     */
    public void invalidate(String filePath) {
        thumbnailCache.remove(filePath);
    }

    /**
     * Get the current cache size.
     */
    public int getCacheSize() {
        return thumbnailCache.size();
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    /**
     * Callback interface for async thumbnail loading.
     */
    public interface ThumbnailCallback {
        void onThumbnailReady(String filePath, Image thumbnail);
    }
}
