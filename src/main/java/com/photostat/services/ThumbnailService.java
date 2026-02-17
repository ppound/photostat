package com.photostat.services;

import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for generating and caching image thumbnails.
 * Supports both in-memory and disk caching with configurable size limits.
 */
public class ThumbnailService {

    private static ThumbnailService instance;
    private final ConfigService configService;
    private final LoggingService logger;

    // In-memory cache for thumbnails (L1 cache)
    private final Map<String, Image> memoryCache = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_CACHE_SIZE = 500;

    // Disk cache directory
    private Path diskCacheDir;

    // Track access times for LRU eviction
    private final Map<String, Long> cacheAccessTimes = new ConcurrentHashMap<>();

    // Shared thread pool for async thumbnail/face crop loading
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("thumbnail-loader-" + t.getId());
        return t;
    });

    // RAW file extensions that need ExifTool for thumbnail extraction
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf",
            ".pef", ".srw", ".x3f", ".raw", ".rwl"
    );

    private ThumbnailService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
        initDiskCache();
    }

    public static synchronized ThumbnailService getInstance() {
        if (instance == null) {
            instance = new ThumbnailService();
        }
        return instance;
    }

    /**
     * Initialize the disk cache directory.
     */
    private void initDiskCache() {
        String userHome = System.getProperty("user.home");
        diskCacheDir = Path.of(userHome, ".photostat", "cache");

        try {
            Files.createDirectories(diskCacheDir);
            logger.info("ThumbnailService", "Disk cache initialized at: " + diskCacheDir);
        } catch (IOException e) {
            logger.error("ThumbnailService", "Failed to create disk cache directory", e);
        }
    }

    /**
     * Get a thumbnail for an image file.
     */
    public Image getThumbnail(String filePath) {
        // Check memory cache first (L1)
        Image cached = memoryCache.get(filePath);
        if (cached != null) {
            updateAccessTime(filePath);
            return cached;
        }

        // Check disk cache (L2)
        if (configService.isThumbnailCacheEnabled()) {
            Image diskCached = loadFromDiskCache(filePath);
            if (diskCached != null) {
                addToMemoryCache(filePath, diskCached);
                return diskCached;
            }
        }

        // Generate thumbnail
        Image thumbnail = generateThumbnail(filePath);
        if (thumbnail != null) {
            addToMemoryCache(filePath, thumbnail);

            // Save to disk cache
            if (configService.isThumbnailCacheEnabled()) {
                saveToDiskCache(filePath, thumbnail);
            }
        }

        return thumbnail;
    }

    /**
     * Add a thumbnail to the memory cache with size limit.
     */
    private void addToMemoryCache(String filePath, Image thumbnail) {
        if (memoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
            // Evict oldest entry from memory cache
            String oldestKey = cacheAccessTimes.entrySet().stream()
                    .filter(e -> memoryCache.containsKey(e.getKey()))
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(memoryCache.keySet().iterator().next());
            memoryCache.remove(oldestKey);
        }
        memoryCache.put(filePath, thumbnail);
        updateAccessTime(filePath);
    }

    /**
     * Update access time for LRU tracking.
     */
    private void updateAccessTime(String filePath) {
        cacheAccessTimes.put(filePath, System.currentTimeMillis());
    }

    /**
     * Generate a cache key based on file path and modification time.
     */
    private String getCacheKey(String filePath) {
        try {
            Path path = Path.of(filePath);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long modTime = attrs.lastModifiedTime().toMillis();
            int thumbSize = configService.getThumbnailSize();

            String input = filePath + "|" + modTime + "|" + thumbSize;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback to simple hash
            return Integer.toHexString(filePath.hashCode());
        }
    }

    /**
     * Get the disk cache file path for a given image.
     */
    public Path getDiskCachePath(String filePath) {
        String cacheKey = getCacheKey(filePath);
        return diskCacheDir.resolve(cacheKey + ".jpg");
    }

    /**
     * Load a thumbnail from the disk cache.
     */
    private Image loadFromDiskCache(String filePath) {
        try {
            Path cachePath = getDiskCachePath(filePath);
            if (Files.exists(cachePath)) {
                // Update file access time for LRU
                Files.setLastModifiedTime(cachePath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

                Image image = new Image(cachePath.toUri().toString());
                if (!image.isError()) {
                    logger.debug("ThumbnailService", "Loaded from disk cache: " + filePath);
                    return image;
                }
            }
        } catch (Exception e) {
            logger.debug("ThumbnailService", "Disk cache load failed for " + filePath + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Save a thumbnail to the disk cache.
     */
    private void saveToDiskCache(String filePath, Image thumbnail) {
        try {
            // Check cache size before saving
            enforceDiskCacheLimit();

            Path cachePath = getDiskCachePath(filePath);

            // Convert JavaFX Image to BufferedImage and save as JPEG
            int width = (int) thumbnail.getWidth();
            int height = (int) thumbnail.getHeight();

            if (width <= 0 || height <= 0) {
                return;
            }

            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            javafx.scene.image.PixelReader reader = thumbnail.getPixelReader();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    javafx.scene.paint.Color fxColor = reader.getColor(x, y);
                    int r = (int) (fxColor.getRed() * 255);
                    int g = (int) (fxColor.getGreen() * 255);
                    int b = (int) (fxColor.getBlue() * 255);
                    int rgb = (r << 16) | (g << 8) | b;
                    bufferedImage.setRGB(x, y, rgb);
                }
            }

            // Save as JPEG with quality setting
            ImageIO.write(bufferedImage, "jpg", cachePath.toFile());
            logger.debug("ThumbnailService", "Saved to disk cache: " + cachePath);

        } catch (Exception e) {
            logger.debug("ThumbnailService", "Failed to save to disk cache: " + e.getMessage());
        }
    }

    /**
     * Enforce the disk cache size limit using LRU eviction.
     */
    private void enforceDiskCacheLimit() {
        try {
            long maxSizeBytes = configService.getThumbnailCacheMaxSizeMB() * 1024L * 1024L;
            long currentSize = getDiskCacheSize();

            if (currentSize > maxSizeBytes) {
                logger.info("ThumbnailService", "Disk cache size (" + (currentSize / 1024 / 1024) + "MB) exceeds limit (" +
                        configService.getThumbnailCacheMaxSizeMB() + "MB), evicting old entries");

                // Get all cache files sorted by last modified time (oldest first)
                File[] cacheFiles = diskCacheDir.toFile().listFiles((dir, name) -> name.endsWith(".jpg"));
                if (cacheFiles != null && cacheFiles.length > 0) {
                    Arrays.sort(cacheFiles, Comparator.comparingLong(File::lastModified));

                    // Delete oldest files until under limit
                    for (File file : cacheFiles) {
                        if (currentSize <= maxSizeBytes * 0.8) {  // Target 80% of max
                            break;
                        }
                        long fileSize = file.length();
                        if (file.delete()) {
                            currentSize -= fileSize;
                            logger.debug("ThumbnailService", "Evicted cache file: " + file.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("ThumbnailService", "Error enforcing cache limit", e);
        }
    }

    /**
     * Get the current disk cache size in bytes.
     */
    public long getDiskCacheSize() {
        try {
            File[] files = diskCacheDir.toFile().listFiles((dir, name) -> name.endsWith(".jpg"));
            if (files != null) {
                long total = 0;
                for (File file : files) {
                    total += file.length();
                }
                return total;
            }
        } catch (Exception e) {
            logger.error("ThumbnailService", "Error calculating cache size", e);
        }
        return 0;
    }

    /**
     * Get the number of files in the disk cache.
     */
    public int getDiskCacheFileCount() {
        try {
            File[] files = diskCacheDir.toFile().listFiles((dir, name) -> name.endsWith(".jpg"));
            return files != null ? files.length : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clear the entire disk cache.
     */
    public void clearDiskCache() {
        try {
            File[] files = diskCacheDir.toFile().listFiles((dir, name) -> name.endsWith(".jpg"));
            if (files != null) {
                int count = 0;
                for (File file : files) {
                    if (file.delete()) {
                        count++;
                    }
                }
                logger.info("ThumbnailService", "Cleared " + count + " files from disk cache");
            }
        } catch (Exception e) {
            logger.error("ThumbnailService", "Error clearing disk cache", e);
        }
    }

    /**
     * Get the disk cache directory path.
     */
    public Path getDiskCacheDir() {
        return diskCacheDir;
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
        thumbnailExecutor.submit(() -> {
            Image thumbnail = getThumbnail(filePath);
            callback.onThumbnailReady(filePath, thumbnail);
        });
    }

    /**
     * Clear the in-memory thumbnail cache.
     */
    public void clearCache() {
        memoryCache.clear();
        cacheAccessTimes.clear();
    }

    /**
     * Clear both memory and disk caches.
     */
    public void clearAllCaches() {
        clearCache();
        clearDiskCache();
    }

    /**
     * Remove a specific thumbnail from both caches.
     */
    public void invalidate(String filePath) {
        memoryCache.remove(filePath);
        cacheAccessTimes.remove(filePath);

        // Also remove from disk cache
        try {
            Path cachePath = getDiskCachePath(filePath);
            Files.deleteIfExists(cachePath);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Get the current memory cache size.
     */
    public int getCacheSize() {
        return memoryCache.size();
    }

    /**
     * Get a face crop from an image, with disk caching in ~/.photostat/faces/crops/.
     */
    public Image getFaceCrop(String imagePath, int x, int y, int w, int h) {
        String cropKey = imagePath + "|" + x + "|" + y + "|" + w + "|" + h;
        String cacheFileName = Integer.toHexString(cropKey.hashCode()) + ".jpg";
        Path cropsDir = Path.of(System.getProperty("user.home"), ".photostat", "faces", "crops");
        Path cropCachePath = cropsDir.resolve(cacheFileName);

        // Check disk cache
        if (Files.exists(cropCachePath)) {
            try {
                Image cached = new Image(cropCachePath.toUri().toString());
                if (!cached.isError()) {
                    return cached;
                }
            } catch (Exception e) {
                // Fall through to generate
            }
        }

        try {
            Path path = Path.of(imagePath);
            if (!Files.exists(path)) {
                return null;
            }

            String extension = getFileExtension(imagePath).toLowerCase();
            BufferedImage sourceImage = null;

            // For RAW files, extract preview first
            if (RAW_EXTENSIONS.contains(extension)) {
                Image preview = generateThumbnailWithExifTool(path, 2000);
                if (preview != null) {
                    // Convert FX Image to BufferedImage
                    int pw = (int) preview.getWidth();
                    int ph = (int) preview.getHeight();
                    sourceImage = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
                    javafx.scene.image.PixelReader reader = preview.getPixelReader();
                    for (int py = 0; py < ph; py++) {
                        for (int px = 0; px < pw; px++) {
                            javafx.scene.paint.Color c = reader.getColor(px, py);
                            int rgb = ((int)(c.getRed()*255) << 16) | ((int)(c.getGreen()*255) << 8) | (int)(c.getBlue()*255);
                            sourceImage.setRGB(px, py, rgb);
                        }
                    }
                }
            } else {
                sourceImage = ImageIO.read(path.toFile());
            }

            if (sourceImage == null) {
                return null;
            }

            // Add 20% padding around face
            int padX = (int) (w * 0.2);
            int padY = (int) (h * 0.2);
            int cropX = Math.max(0, x - padX);
            int cropY = Math.max(0, y - padY);
            int cropW = Math.min(w + 2 * padX, sourceImage.getWidth() - cropX);
            int cropH = Math.min(h + 2 * padY, sourceImage.getHeight() - cropY);

            BufferedImage cropped = sourceImage.getSubimage(cropX, cropY, cropW, cropH);
            BufferedImage scaled = scaleImage(cropped, 120);

            // Save to disk cache
            try {
                Files.createDirectories(cropsDir);
                ImageIO.write(scaled, "jpg", cropCachePath.toFile());
            } catch (IOException e) {
                logger.debug("ThumbnailService", "Failed to cache face crop: " + e.getMessage());
            }

            return convertToFxImage(scaled);
        } catch (Exception e) {
            logger.debug("ThumbnailService", "Failed to generate face crop: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get a face crop asynchronously.
     */
    public void getFaceCropAsync(String imagePath, int x, int y, int w, int h, ThumbnailCallback callback) {
        thumbnailExecutor.submit(() -> {
            Image crop = getFaceCrop(imagePath, x, y, w, h);
            callback.onThumbnailReady(imagePath, crop);
        });
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
