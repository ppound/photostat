package com.photostat.services;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExifService parsing and utility methods.
 */
public class ExifServiceTest {

    private static ExifService exifService;

    @BeforeAll
    static void setUp() {
        exifService = ExifService.getInstance();
    }

    // --- parseDate ---

    @Test
    void parseDateColonFormat() {
        LocalDateTime result = exifService.parseDate("2024:01:15 14:30:00");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    @Test
    void parseDateDashFormat() {
        LocalDateTime result = exifService.parseDate("2024-01-15 14:30:00");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    @Test
    void parseDateSlashFormat() {
        LocalDateTime result = exifService.parseDate("2024/01/15 14:30:00");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    @Test
    void parseDateIsoTFormat() {
        LocalDateTime result = exifService.parseDate("2024:01:15T14:30:00");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    @Test
    void parseDateIsoLocalDateTime() {
        LocalDateTime result = exifService.parseDate("2024-01-15T14:30:00");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    @Test
    void parseDateFallbackRegex() {
        // Unusual separator that none of the formatters handle but the regex can extract
        LocalDateTime result = exifService.parseDate("Date: 2024-06-20 some text 10:15:30");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 6, 20, 10, 15, 30), result);
    }

    @Test
    void parseDateNull() {
        assertNull(exifService.parseDate(null));
    }

    @Test
    void parseDateEmpty() {
        assertNull(exifService.parseDate(""));
    }

    @Test
    void parseDateInvalid() {
        assertNull(exifService.parseDate("not a date"));
    }

    @Test
    void parseDateWithLeadingTrailingWhitespace() {
        LocalDateTime result = exifService.parseDate("  2024:01:15 14:30:00  ");
        assertNotNull(result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0), result);
    }

    // --- parseFocalLength ---

    @Test
    void parseFocalLengthWithMm() {
        assertEquals(50.0, exifService.parseFocalLength("50 mm"));
    }

    @Test
    void parseFocalLengthWithMmNoSpace() {
        assertEquals(50.0, exifService.parseFocalLength("50mm"));
    }

    @Test
    void parseFocalLengthDecimal() {
        assertEquals(35.5, exifService.parseFocalLength("35.5 mm"));
    }

    @Test
    void parseFocalLengthPlainNumber() {
        assertEquals(200.0, exifService.parseFocalLength("200"));
    }

    @Test
    void parseFocalLengthNull() {
        assertNull(exifService.parseFocalLength(null));
    }

    @Test
    void parseFocalLengthEmpty() {
        assertNull(exifService.parseFocalLength(""));
    }

    // --- cleanString ---

    @Test
    void cleanStringNormal() {
        assertEquals("Canon", exifService.cleanString("Canon"));
    }

    @Test
    void cleanStringWithControlChars() {
        assertEquals("Canon EOS R5", exifService.cleanString("Canon\u0000 EOS\u001F R5"));
    }

    @Test
    void cleanStringTrimsWhitespace() {
        assertEquals("Nikon", exifService.cleanString("  Nikon  "));
    }

    @Test
    void cleanStringNull() {
        assertNull(exifService.cleanString(null));
    }

    // --- getFileExtension ---

    @Test
    void getFileExtensionJpeg() {
        assertEquals(".jpg", exifService.getFileExtension("photo.jpg"));
    }

    @Test
    void getFileExtensionRaw() {
        assertEquals(".CR2", exifService.getFileExtension("IMG_1234.CR2"));
    }

    @Test
    void getFileExtensionMultipleDots() {
        assertEquals(".json", exifService.getFileExtension("photo.jpg.photostat.json"));
    }

    @Test
    void getFileExtensionNone() {
        assertEquals("", exifService.getFileExtension("README"));
    }

    // --- getStringValue ---

    @Test
    void getStringValueFirstKeyMatch() {
        Map<String, Object> data = new HashMap<>();
        data.put("Make", "Canon");
        assertEquals("Canon", exifService.getStringValue(data, "Make", "Manufacturer"));
    }

    @Test
    void getStringValueFallbackKey() {
        Map<String, Object> data = new HashMap<>();
        data.put("Manufacturer", "Nikon");
        assertEquals("Nikon", exifService.getStringValue(data, "Make", "Manufacturer"));
    }

    @Test
    void getStringValueNoMatch() {
        Map<String, Object> data = new HashMap<>();
        data.put("ISO", 100);
        assertNull(exifService.getStringValue(data, "Make", "Manufacturer"));
    }

    @Test
    void getStringValueCleansControlChars() {
        Map<String, Object> data = new HashMap<>();
        data.put("Make", "Canon\u0000");
        assertEquals("Canon", exifService.getStringValue(data, "Make"));
    }

    // --- getIntValue ---

    @Test
    void getIntValueFromNumber() {
        Map<String, Object> data = new HashMap<>();
        data.put("ISO", 400);
        assertEquals(400, exifService.getIntValue(data, "ISO"));
    }

    @Test
    void getIntValueFromDouble() {
        Map<String, Object> data = new HashMap<>();
        data.put("ISO", 400.0);
        assertEquals(400, exifService.getIntValue(data, "ISO"));
    }

    @Test
    void getIntValueFromString() {
        Map<String, Object> data = new HashMap<>();
        data.put("ISO", "800");
        assertEquals(800, exifService.getIntValue(data, "ISO"));
    }

    @Test
    void getIntValueFromStringWithUnits() {
        Map<String, Object> data = new HashMap<>();
        data.put("ImageWidth", "4000 pixels");
        assertEquals(4000, exifService.getIntValue(data, "ImageWidth"));
    }

    @Test
    void getIntValueFallbackKey() {
        Map<String, Object> data = new HashMap<>();
        data.put("ExifImageWidth", 6000);
        assertEquals(6000, exifService.getIntValue(data, "ImageWidth", "ExifImageWidth"));
    }

    @Test
    void getIntValueNoMatch() {
        Map<String, Object> data = new HashMap<>();
        assertNull(exifService.getIntValue(data, "ISO"));
    }

    // --- getDoubleValue ---

    @Test
    void getDoubleValueFromNumber() {
        Map<String, Object> data = new HashMap<>();
        data.put("FNumber", 2.8);
        assertEquals(2.8, exifService.getDoubleValue(data, "FNumber"));
    }

    @Test
    void getDoubleValueFromInteger() {
        Map<String, Object> data = new HashMap<>();
        data.put("FNumber", 4);
        assertEquals(4.0, exifService.getDoubleValue(data, "FNumber"));
    }

    @Test
    void getDoubleValueFromString() {
        Map<String, Object> data = new HashMap<>();
        data.put("FNumber", "5.6");
        assertEquals(5.6, exifService.getDoubleValue(data, "FNumber"));
    }

    @Test
    void getDoubleValueFromFStopString() {
        Map<String, Object> data = new HashMap<>();
        data.put("Aperture", "f/2.8");
        assertEquals(2.8, exifService.getDoubleValue(data, "Aperture"));
    }

    @Test
    void getDoubleValueFallbackKey() {
        Map<String, Object> data = new HashMap<>();
        data.put("Aperture", 1.4);
        assertEquals(1.4, exifService.getDoubleValue(data, "FNumber", "Aperture"));
    }

    @Test
    void getDoubleValueNoMatch() {
        Map<String, Object> data = new HashMap<>();
        assertNull(exifService.getDoubleValue(data, "FNumber"));
    }
}
