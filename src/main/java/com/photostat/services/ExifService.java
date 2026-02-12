package com.photostat.services;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.file.FileSystemDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photostat.models.ImageMetadata;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for extracting EXIF metadata from image files.
 */
public class ExifService {

    private static ExifService instance;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    // RAW file extensions that require ExifTool
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf",
            ".pef", ".srw", ".x3f", ".raw", ".rwl"
    );

    // Date formats to try when parsing
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy:MM:dd'T'HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private ExifService() {
        this.configService = ConfigService.getInstance();
        this.objectMapper = new ObjectMapper();
    }

    public static synchronized ExifService getInstance() {
        if (instance == null) {
            instance = new ExifService();
        }
        return instance;
    }

    /**
     * Extract metadata from an image file.
     */
    public ImageMetadata extractMetadata(Path filePath) {
        ImageMetadata metadata = new ImageMetadata();

        // Set basic file info
        File file = filePath.toFile();
        metadata.setFilePath(filePath.toAbsolutePath().toString());
        metadata.setFileName(file.getName());
        metadata.setFileType(getFileExtension(file.getName()).toLowerCase());

        try {
            metadata.setFileSize(Files.size(filePath));
        } catch (IOException e) {
            System.err.println("Error getting file size: " + e.getMessage());
        }

        // Check if it's a RAW file that needs ExifTool
        String extension = getFileExtension(file.getName()).toLowerCase();
        if (RAW_EXTENSIONS.contains(extension) && configService.isUseExifToolForRaw()) {
            extractWithExifTool(filePath, metadata);
        } else {
            extractWithMetadataExtractor(filePath, metadata);
        }

        return metadata;
    }

    /**
     * Extract metadata using metadata-extractor library.
     */
    private void extractWithMetadataExtractor(Path filePath, ImageMetadata metadata) {
        try {
            Metadata exifMetadata = ImageMetadataReader.readMetadata(filePath.toFile());
            Map<String, Object> allExif = new HashMap<>();

            // Process each directory
            for (Directory directory : exifMetadata.getDirectories()) {
                String dirName = directory.getName();

                // Collect all tags
                for (Tag tag : directory.getTags()) {
                    String tagName = tag.getTagName();
                    String tagValue = tag.getDescription();
                    if (tagValue != null && !tagValue.isEmpty()) {
                        allExif.put(dirName + ":" + tagName, tagValue);
                    }
                }

                // Extract specific fields
                if (directory instanceof ExifIFD0Directory) {
                    extractExifIFD0(directory, metadata);
                } else if (directory instanceof ExifSubIFDDirectory) {
                    extractExifSubIFD(directory, metadata);
                } else if (directory instanceof GpsDirectory) {
                    extractGps((GpsDirectory) directory, metadata);
                } else if (directory instanceof JpegDirectory) {
                    extractJpegInfo((JpegDirectory) directory, metadata);
                } else if (directory instanceof PngDirectory) {
                    extractPngInfo((PngDirectory) directory, metadata);
                }
            }

            metadata.setAllExif(allExif);

        } catch (Exception e) {
            System.err.println("Error extracting metadata from " + filePath + ": " + e.getMessage());
        }
    }

    private void extractExifIFD0(Directory directory, ImageMetadata metadata) {
        // Camera make
        if (directory.containsTag(ExifIFD0Directory.TAG_MAKE)) {
            metadata.setCameraMake(cleanString(directory.getString(ExifIFD0Directory.TAG_MAKE)));
        }

        // Camera model
        if (directory.containsTag(ExifIFD0Directory.TAG_MODEL)) {
            metadata.setCameraModel(cleanString(directory.getString(ExifIFD0Directory.TAG_MODEL)));
        }

        // Software
        if (directory.containsTag(ExifIFD0Directory.TAG_SOFTWARE)) {
            metadata.setSoftware(cleanString(directory.getString(ExifIFD0Directory.TAG_SOFTWARE)));
        }

        // Artist
        if (directory.containsTag(ExifIFD0Directory.TAG_ARTIST)) {
            metadata.setArtist(cleanString(directory.getString(ExifIFD0Directory.TAG_ARTIST)));
        }

        // Copyright
        if (directory.containsTag(ExifIFD0Directory.TAG_COPYRIGHT)) {
            metadata.setCopyright(cleanString(directory.getString(ExifIFD0Directory.TAG_COPYRIGHT)));
        }

        // Orientation
        if (directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            metadata.setOrientation(directory.getDescription(ExifIFD0Directory.TAG_ORIENTATION));
        }

        // Image dimensions
        if (directory.containsTag(ExifIFD0Directory.TAG_IMAGE_WIDTH)) {
            try {
                metadata.setImageWidth(directory.getInt(ExifIFD0Directory.TAG_IMAGE_WIDTH));
            } catch (Exception ignored) {}
        }
        if (directory.containsTag(ExifIFD0Directory.TAG_IMAGE_HEIGHT)) {
            try {
                metadata.setImageHeight(directory.getInt(ExifIFD0Directory.TAG_IMAGE_HEIGHT));
            } catch (Exception ignored) {}
        }
    }

    private void extractExifSubIFD(Directory directory, ImageMetadata metadata) {
        // Date taken
        if (directory.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)) {
            String dateStr = directory.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            metadata.setDateTaken(parseDate(dateStr));
        }

        // ISO
        if (directory.containsTag(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)) {
            try {
                metadata.setIso(directory.getInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
            } catch (Exception ignored) {}
        }

        // Aperture
        if (directory.containsTag(ExifSubIFDDirectory.TAG_FNUMBER)) {
            try {
                metadata.setAperture(directory.getDouble(ExifSubIFDDirectory.TAG_FNUMBER));
            } catch (Exception ignored) {}
        }

        // Shutter speed
        if (directory.containsTag(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)) {
            metadata.setShutterSpeed(directory.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
        }

        // Focal length
        if (directory.containsTag(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)) {
            try {
                metadata.setFocalLength(directory.getDouble(ExifSubIFDDirectory.TAG_FOCAL_LENGTH));
            } catch (Exception ignored) {}
        }

        // Lens model
        if (directory.containsTag(ExifSubIFDDirectory.TAG_LENS_MODEL)) {
            metadata.setLensModel(cleanString(directory.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)));
        }

        // Image dimensions (if not already set)
        if (metadata.getImageWidth() == null && directory.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)) {
            try {
                metadata.setImageWidth(directory.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH));
            } catch (Exception ignored) {}
        }
        if (metadata.getImageHeight() == null && directory.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)) {
            try {
                metadata.setImageHeight(directory.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT));
            } catch (Exception ignored) {}
        }
    }

    private void extractGps(GpsDirectory directory, ImageMetadata metadata) {
        if (directory.getGeoLocation() != null) {
            metadata.setGpsLatitude(directory.getGeoLocation().getLatitude());
            metadata.setGpsLongitude(directory.getGeoLocation().getLongitude());
        }
    }

    private void extractJpegInfo(JpegDirectory directory, ImageMetadata metadata) {
        if (metadata.getImageWidth() == null && directory.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) {
            try {
                metadata.setImageWidth(directory.getInt(JpegDirectory.TAG_IMAGE_WIDTH));
            } catch (Exception ignored) {}
        }
        if (metadata.getImageHeight() == null && directory.containsTag(JpegDirectory.TAG_IMAGE_HEIGHT)) {
            try {
                metadata.setImageHeight(directory.getInt(JpegDirectory.TAG_IMAGE_HEIGHT));
            } catch (Exception ignored) {}
        }
    }

    private void extractPngInfo(PngDirectory directory, ImageMetadata metadata) {
        if (metadata.getImageWidth() == null && directory.containsTag(PngDirectory.TAG_IMAGE_WIDTH)) {
            try {
                metadata.setImageWidth(directory.getInt(PngDirectory.TAG_IMAGE_WIDTH));
            } catch (Exception ignored) {}
        }
        if (metadata.getImageHeight() == null && directory.containsTag(PngDirectory.TAG_IMAGE_HEIGHT)) {
            try {
                metadata.setImageHeight(directory.getInt(PngDirectory.TAG_IMAGE_HEIGHT));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Extract metadata using ExifTool for RAW files.
     */
    private void extractWithExifTool(Path filePath, ImageMetadata metadata) {
        String exifToolPath = configService.getExifToolPath();
        Map<String, Object> allExif = new HashMap<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exifToolPath, "-json", "-all", filePath.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.length() > 0) {
                // Parse JSON output
                List<Map<String, Object>> results = objectMapper.readValue(
                        output.toString(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );

                if (!results.isEmpty()) {
                    Map<String, Object> exifData = results.get(0);
                    allExif.putAll(exifData);

                    // Extract specific fields
                    metadata.setCameraMake(getStringValue(exifData, "Make"));
                    metadata.setCameraModel(getStringValue(exifData, "Model"));
                    metadata.setLensModel(getStringValue(exifData, "LensModel", "LensID", "Lens"));
                    metadata.setSoftware(getStringValue(exifData, "Software"));
                    metadata.setArtist(getStringValue(exifData, "Artist", "Creator"));
                    metadata.setCopyright(getStringValue(exifData, "Copyright"));
                    metadata.setOrientation(getStringValue(exifData, "Orientation"));

                    // ISO
                    Integer iso = getIntValue(exifData, "ISO");
                    if (iso != null) metadata.setIso(iso);

                    // Aperture
                    Double aperture = getDoubleValue(exifData, "FNumber", "Aperture");
                    if (aperture != null) metadata.setAperture(aperture);

                    // Shutter speed
                    metadata.setShutterSpeed(getStringValue(exifData, "ShutterSpeed", "ExposureTime"));

                    // Focal length
                    Double focalLength = parseFocalLength(getStringValue(exifData, "FocalLength"));
                    if (focalLength != null) metadata.setFocalLength(focalLength);

                    // Dimensions
                    Integer width = getIntValue(exifData, "ImageWidth", "ExifImageWidth");
                    Integer height = getIntValue(exifData, "ImageHeight", "ExifImageHeight");
                    if (width != null) metadata.setImageWidth(width);
                    if (height != null) metadata.setImageHeight(height);

                    // Date taken
                    String dateStr = getStringValue(exifData, "DateTimeOriginal", "CreateDate");
                    if (dateStr != null) {
                        metadata.setDateTaken(parseDate(dateStr));
                    }

                    // GPS
                    Double lat = getDoubleValue(exifData, "GPSLatitude");
                    Double lon = getDoubleValue(exifData, "GPSLongitude");
                    if (lat != null && lon != null) {
                        // Handle GPS reference
                        String latRef = getStringValue(exifData, "GPSLatitudeRef");
                        String lonRef = getStringValue(exifData, "GPSLongitudeRef");
                        if ("S".equalsIgnoreCase(latRef)) lat = -lat;
                        if ("W".equalsIgnoreCase(lonRef)) lon = -lon;
                        metadata.setGpsLatitude(lat);
                        metadata.setGpsLongitude(lon);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("ExifTool extraction failed for " + filePath + ": " + e.getMessage());
            // Fall back to metadata-extractor
            extractWithMetadataExtractor(filePath, metadata);
            return;
        }

        metadata.setAllExif(allExif);
    }

    /**
     * Parse a date string to LocalDateTime.
     */
    LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(dateStr.trim(), formatter);
            } catch (DateTimeParseException ignored) {}
        }

        // Try to extract just the date part
        Pattern datePattern = Pattern.compile("(\\d{4})[:\\-/](\\d{2})[:\\-/](\\d{2}).*?(\\d{2}):(\\d{2}):(\\d{2})");
        Matcher matcher = datePattern.matcher(dateStr);
        if (matcher.find()) {
            try {
                return LocalDateTime.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)),
                        Integer.parseInt(matcher.group(6))
                );
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * Parse focal length string (e.g., "50 mm" or "50.0mm") to Double.
     */
    Double parseFocalLength(String value) {
        if (value == null || value.isEmpty()) return null;

        Pattern pattern = Pattern.compile("([\\d.]+)\\s*(?:mm)?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Get string value from ExifTool map, trying multiple keys.
     */
    String getStringValue(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                return cleanString(value.toString());
            }
        }
        return null;
    }

    /**
     * Get integer value from ExifTool map, trying multiple keys.
     */
    Integer getIntValue(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                try {
                    return Integer.parseInt(value.toString().replaceAll("[^\\d]", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /**
     * Get double value from ExifTool map, trying multiple keys.
     */
    Double getDoubleValue(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                try {
                    // Handle strings like "5.6" or "f/5.6"
                    String str = value.toString().replaceAll("[^\\d.]", "");
                    return Double.parseDouble(str);
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /**
     * Clean string by trimming and removing control characters.
     */
    String cleanString(String value) {
        if (value == null) return null;
        return value.trim().replaceAll("[\\x00-\\x1F]", "");
    }

    /**
     * Get file extension including the dot.
     */
    String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    /**
     * Check if a file is a supported image format.
     */
    public boolean isSupportedFormat(String filename) {
        String ext = getFileExtension(filename).toLowerCase();
        return configService.getFileExtensions().contains(ext);
    }

    /**
     * Check if ExifTool is available on the system.
     */
    public boolean isExifToolAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(configService.getExifToolPath(), "-ver");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
