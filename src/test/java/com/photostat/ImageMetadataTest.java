package com.photostat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.photostat.models.ImageMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageMetadata model.
 */
public class ImageMetadataTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void testBasicProperties() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setFilePath("/path/to/image.jpg");
        metadata.setFileName("image.jpg");
        metadata.setFileType(".jpg");
        metadata.setFileSize(1024L * 1024L); // 1 MB

        assertEquals("/path/to/image.jpg", metadata.getFilePath());
        assertEquals("image.jpg", metadata.getFileName());
        assertEquals(".jpg", metadata.getFileType());
        assertEquals(1024L * 1024L, metadata.getFileSize());
    }

    @Test
    void testCameraProperties() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setCameraMake("Canon");
        metadata.setCameraModel("EOS R5");
        metadata.setLensModel("RF 50mm F1.2L USM");

        assertEquals("Canon", metadata.getCameraMake());
        assertEquals("EOS R5", metadata.getCameraModel());
        assertEquals("RF 50mm F1.2L USM", metadata.getLensModel());
    }

    @Test
    void testExposureProperties() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setIso(100);
        metadata.setAperture(2.8);
        metadata.setShutterSpeed("1/250");
        metadata.setFocalLength(50.0);

        assertEquals(100, metadata.getIso());
        assertEquals(2.8, metadata.getAperture());
        assertEquals("1/250", metadata.getShutterSpeed());
        assertEquals(50.0, metadata.getFocalLength());
    }

    @Test
    void testGpsLocation() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setGpsLatitude(37.7749);
        metadata.setGpsLongitude(-122.4194);

        assertEquals(37.7749, metadata.getGpsLatitude());
        assertEquals(-122.4194, metadata.getGpsLongitude());

        // Check that gps_location map is created
        assertNotNull(metadata.getGpsLocation());
        assertEquals(37.7749, metadata.getGpsLocation().get("lat"));
        assertEquals(-122.4194, metadata.getGpsLocation().get("lon"));
    }

    @Test
    void testDateTaken() {
        ImageMetadata metadata = new ImageMetadata();

        LocalDateTime dateTaken = LocalDateTime.of(2024, 1, 15, 14, 30, 0);
        metadata.setDateTaken(dateTaken);

        assertEquals(dateTaken, metadata.getDateTaken());
    }

    @Test
    void testFileSizeString() {
        ImageMetadata metadata = new ImageMetadata();

        // Bytes
        metadata.setFileSize(500L);
        assertEquals("500 B", metadata.getFileSizeString());

        // Kilobytes
        metadata.setFileSize(1536L);
        assertEquals("1.5 KB", metadata.getFileSizeString());

        // Megabytes
        metadata.setFileSize(5L * 1024L * 1024L);
        assertEquals("5.0 MB", metadata.getFileSizeString());

        // Gigabytes
        metadata.setFileSize(2L * 1024L * 1024L * 1024L);
        assertEquals("2.0 GB", metadata.getFileSizeString());
    }

    @Test
    void testApertureString() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setAperture(1.4);
        assertEquals("f/1.4", metadata.getApertureString());

        metadata.setAperture(5.6);
        assertEquals("f/5.6", metadata.getApertureString());

        metadata.setAperture(null);
        assertEquals("", metadata.getApertureString());
    }

    @Test
    void testFocalLengthString() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setFocalLength(35.0);
        assertEquals("35mm", metadata.getFocalLengthString());

        metadata.setFocalLength(200.0);
        assertEquals("200mm", metadata.getFocalLengthString());

        metadata.setFocalLength(null);
        assertEquals("", metadata.getFocalLengthString());
    }

    @Test
    void testDimensionsString() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.setImageWidth(4000);
        metadata.setImageHeight(3000);
        assertEquals("4000 x 3000", metadata.getDimensionsString());

        metadata.setImageWidth(null);
        assertEquals("", metadata.getDimensionsString());
    }

    @Test
    void testAllExif() {
        ImageMetadata metadata = new ImageMetadata();

        metadata.addExifTag("EXIF:ISO", "100");
        metadata.addExifTag("EXIF:FNumber", "2.8");

        assertEquals("100", metadata.getAllExif().get("EXIF:ISO"));
        assertEquals("2.8", metadata.getAllExif().get("EXIF:FNumber"));
    }

    @Test
    void testJsonSerialization() throws Exception {
        ImageMetadata metadata = new ImageMetadata();
        metadata.setFilePath("/test/image.jpg");
        metadata.setFileName("image.jpg");
        metadata.setCameraMake("Nikon");
        metadata.setCameraModel("Z6");
        metadata.setIso(400);
        metadata.setDateTaken(LocalDateTime.of(2024, 6, 15, 10, 30, 0));

        // Serialize
        String json = objectMapper.writeValueAsString(metadata);

        // Verify JSON contains expected fields
        assertTrue(json.contains("file_path"));
        assertTrue(json.contains("/test/image.jpg"));
        assertTrue(json.contains("camera_make"));
        assertTrue(json.contains("Nikon"));

        // Deserialize
        ImageMetadata deserialized = objectMapper.readValue(json, ImageMetadata.class);

        assertEquals("/test/image.jpg", deserialized.getFilePath());
        assertEquals("image.jpg", deserialized.getFileName());
        assertEquals("Nikon", deserialized.getCameraMake());
        assertEquals("Z6", deserialized.getCameraModel());
        assertEquals(400, deserialized.getIso());
    }

    @Test
    void testToString() {
        ImageMetadata metadata = new ImageMetadata();
        metadata.setFileName("test.jpg");
        metadata.setCameraMake("Sony");
        metadata.setCameraModel("A7III");
        metadata.setIso(800);

        String str = metadata.toString();

        assertTrue(str.contains("test.jpg"));
        assertTrue(str.contains("Sony"));
        assertTrue(str.contains("A7III"));
        assertTrue(str.contains("800"));
    }
}
