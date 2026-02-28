package com.photostat.services;

import javafx.embed.swing.SwingFXUtils;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for generating and caching image thumbnails.
 * Supports both in-memory and disk caching with configurable size limits.
 */
public class ThumbnailService {

    private static ThumbnailService instance;
    private final ConfigService configService;
    private final LoggingService logger;

    // In-memory LRU cache for thumbnails (L1 cache) — self-evicting LinkedHashMap
    private static final int MAX_MEMORY_CACHE_SIZE = 500;
    private final Map<String, Image> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_MEMORY_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MAX_MEMORY_CACHE_SIZE;
                }
            });

    // Disk cache directory
    private Path diskCacheDir;

    // Counter for throttling disk cache enforcement
    private final AtomicInteger diskSaveCount = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong diskCacheSize = new java.util.concurrent.atomic.AtomicLong(-1);

    // Shared thread pool for async thumbnail/face crop loading
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2, r -> {
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
        // Check memory cache first (L1) — get() updates LRU order automatically
        Image cached = memoryCache.get(filePath);
        if (cached != null) {
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
     * Add a thumbnail to the memory cache. Eviction is handled automatically by
     * the LinkedHashMap's removeEldestEntry override.
     */
    private void addToMemoryCache(String filePath, Image thumbnail) {
        memoryCache.put(filePath, thumbnail);
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
            Path cachePath = getDiskCachePath(filePath);

            // Convert JavaFX Image to BufferedImage using SwingFXUtils (avoids per-pixel loop)
            int width = (int) thumbnail.getWidth();
            int height = (int) thumbnail.getHeight();
            if (width <= 0 || height <= 0) return;

            BufferedImage argb = SwingFXUtils.fromFXImage(thumbnail, null);
            if (argb == null) return;

            // Convert ARGB → RGB for JPEG (JPEG doesn't support alpha)
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bufferedImage.createGraphics();
            g2d.drawImage(argb, 0, 0, null);
            g2d.dispose();

            ImageIO.write(bufferedImage, "jpg", cachePath.toFile());
            
            long newFileSize = Files.size(cachePath);
            if (diskCacheSize.get() != -1) {
                diskCacheSize.addAndGet(newFileSize);
            }

            logger.debug("ThumbnailService", "Saved to disk cache: " + cachePath);

            // Enforce disk cache limit periodically
            if (diskSaveCount.incrementAndGet() % 50 == 0) {
                thumbnailExecutor.submit(this::enforceDiskCacheLimit);
            }

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
            long currentSize = getDiskCacheSize(); // triggers initial calculation if needed

            if (currentSize > maxSizeBytes) {
                logger.info("ThumbnailService", "Disk cache size (" + (currentSize / 1024 / 1024) + "MB) exceeds limit (" +
                        configService.getThumbnailCacheMaxSizeMB() + "MB), evicting entries");

                // Instead of full sort, collect up to 1000 files via DirectoryStream (effectively a sample)
                java.util.List<File> sample = new ArrayList<>();
                try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(diskCacheDir, "*.jpg")) {
                    for (Path path : stream) {
                        sample.add(path.toFile());
                        if (sample.size() >= 1000) break;
                    }
                }
                
                // Sort the sample by oldest first
                sample.sort(Comparator.comparingLong(File::lastModified));

                // Delete oldest files until under limit
                for (File file : sample) {
                    if (currentSize <= maxSizeBytes * 0.9) {  // Target 90% of max
                        break;
                    }
                    long fileSize = file.length();
                    if (file.delete()) {
                        currentSize = diskCacheSize.addAndGet(-fileSize);
                        logger.debug("ThumbnailService", "Evicted cache file: " + file.getName());
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
        if (diskCacheSize.get() == -1) {
            try {
                long total = 0;
                if (diskCacheDir != null && Files.exists(diskCacheDir)) {
                    try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(diskCacheDir, "*.jpg")) {
                        for (Path entry : stream) {
                            total += Files.size(entry);
                        }
                    }
                }
                diskCacheSize.compareAndSet(-1, total);
            } catch (Exception e) {
                logger.error("ThumbnailService", "Error calculating cache size", e);
                return 0;
            }
        }
        return diskCacheSize.get();
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
                diskCacheSize.set(0);
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

        // Fallback to ImageReader with progressive subsampling to save memory
        try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            
            javax.imageio.ImageReader reader = readers.next();
            reader.setInput(iis, true, true);
            
            int origWidth = reader.getWidth(0);
            int origHeight = reader.getHeight(0);
            
            int scale = Math.min(origWidth / maxSize, origHeight / maxSize);
            int subsampling = 1;
            while (subsampling * 2 <= scale) {
                subsampling *= 2;
            }
            
            javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
            if (subsampling > 1) {
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
            }
            
            BufferedImage originalImage = reader.read(0, param);
            reader.dispose();

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
        byte[] imageData = null;

        try {
            // First try -PreviewImage (most common for modern RAWs)
            imageData = runExifToolExtract("-PreviewImage", path);

            // If that fails, try -JpgFromRaw (older formats or specific cameras)
            if (imageData == null || imageData.length == 0) {
                imageData = runExifToolExtract("-JpgFromRaw", path);
            }
            // If that fails, try -ThumbnailImage (smallest, but always present if available)
            if (imageData == null || imageData.length == 0) {
                imageData = runExifToolExtract("-ThumbnailImage", path);
            }

            if (imageData != null && imageData.length > 100) {
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
     * Run ExifTool to extract an embedded image tag. Returns the raw bytes,
     * or null if extraction failed. Ensures the process is always cleaned up.
     */
    private byte[] runExifToolExtract(String tag, Path path) {
        try {
            ExifToolWorker worker = ExifToolWorker.getInstance();
            if (worker == null) return null;
            
            // Execute with JSON format and Binary tags included
            String outputStr = worker.execute("-j", "-b", tag, path.toString());
            
            if (outputStr != null && outputStr.trim().startsWith("[")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(outputStr);
                if (root.isArray() && root.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode result = root.get(0);
                    // Extract tag name without the leading hyphen
                    String tagName = tag.startsWith("-") ? tag.substring(1) : tag;
                    com.fasterxml.jackson.databind.JsonNode tagNode = result.get(tagName);
                    
                    if (tagNode != null && !tagNode.isNull()) {
                        String b64 = tagNode.asText();
                        if (b64.startsWith("base64:")) {
                            return java.util.Base64.getDecoder().decode(b64.substring(7));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("ExifTool worker extraction failed for " + path + ": " + e.getMessage());
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
     * Convert a BufferedImage to a JavaFX Image using SwingFXUtils (no encoding round-trip).
     */
    private Image convertToFxImage(BufferedImage bufferedImage) {
        return SwingFXUtils.toFXImage(bufferedImage, null);
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

        // Also remove from disk cache
        try {
            Path cachePath = getDiskCachePath(filePath);
            if (Files.exists(cachePath)) {
                long size = Files.size(cachePath);
                if (Files.deleteIfExists(cachePath)) {
                    if (diskCacheSize.get() != -1) {
                        diskCacheSize.addAndGet(-size);
                    }
                }
            }
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
        String cropKey = "face|" + imagePath + "|" + x + "|" + y + "|" + w + "|" + h;
        String cacheFileName = Integer.toHexString(cropKey.hashCode()) + ".jpg";
        Path cropsDir = Path.of(System.getProperty("user.home"), ".photostat", "faces", "crops");
        Path cropCachePath = cropsDir.resolve(cacheFileName);

        // Check in-memory cache first — get() updates LRU order automatically
        Image memoryCached = memoryCache.get(cropKey);
        if (memoryCached != null) {
            return memoryCached;
        }

        // Check disk cache
        if (Files.exists(cropCachePath)) {
            try {
                Image cached = new Image(cropCachePath.toUri().toString());
                if (!cached.isError()) {
                    addToMemoryCache(cropKey, cached);
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
                    // Convert FX Image to BufferedImage using SwingFXUtils (avoids per-pixel loop)
                    int pw = (int) preview.getWidth();
                    int ph = (int) preview.getHeight();
                    BufferedImage argb = SwingFXUtils.fromFXImage(preview, null);
                    sourceImage = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = sourceImage.createGraphics();
                    g2d.drawImage(argb, 0, 0, null);
                    g2d.dispose();
                }
            } else {
                // Use ImageReader with SourceRegion to only decode the necessary face pixels
                try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(path.toFile())) {
                    Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
                    if (readers.hasNext()) {
                        javax.imageio.ImageReader reader = readers.next();
                        reader.setInput(iis, true, true);
                        
                        int origWidth = reader.getWidth(0);
                        int origHeight = reader.getHeight(0);
                        
                        // Add 20% padding around face based on original coordinates
                        int padX = (int) (w * 0.2);
                        int padY = (int) (h * 0.2);
                        int cropX = Math.max(0, x - padX);
                        int cropY = Math.max(0, y - padY);
                        int cropW = Math.min(w + 2 * padX, origWidth - cropX);
                        int cropH = Math.min(h + 2 * padY, origHeight - cropY);
                        
                        javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                        param.setSourceRegion(new Rectangle(cropX, cropY, cropW, cropH));
                        
                        sourceImage = reader.read(0, param);
                        reader.dispose();
                    }
                }
            }

            if (sourceImage == null) {
                return null;
            }

            // The image is already cropped tightly to the face + padding
            BufferedImage scaled = scaleImage(sourceImage, 120);

            // Save to disk cache
            try {
                Files.createDirectories(cropsDir);
                ImageIO.write(scaled, "jpg", cropCachePath.toFile());
            } catch (IOException e) {
                logger.debug("ThumbnailService", "Failed to cache face crop: " + e.getMessage());
            }

            Image result = convertToFxImage(scaled);
            if (result != null) {
                addToMemoryCache(cropKey, result);
            }
            return result;
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
