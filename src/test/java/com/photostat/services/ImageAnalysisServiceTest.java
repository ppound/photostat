package com.photostat.services;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageAnalysisService parsing and utility methods.
 */
public class ImageAnalysisServiceTest {

    private static ImageAnalysisService service;

    @BeforeAll
    static void setUp() {
        service = ImageAnalysisService.getInstance();
    }

    // --- extractJson ---

    @Test
    void extractJsonPlainObject() {
        String input = "{\"tags\": [\"landscape\"], \"rating\": \"***\"}";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("\"tags\""));
    }

    @Test
    void extractJsonMarkdownCodeBlock() {
        String input = "```json\n{\"tags\": [\"portrait\"], \"rating\": \"****\"}\n```";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("\"portrait\""));
    }

    @Test
    void extractJsonMarkdownCodeBlockNoLang() {
        String input = "```\n{\"tags\": [\"nature\"]}\n```";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.contains("\"nature\""));
    }

    @Test
    void extractJsonEmbeddedInText() {
        String input = "Here is the analysis:\n{\"tags\": [\"sunset\"], \"place\": \"beach\"}\nHope this helps!";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"sunset\""));
    }

    @Test
    void extractJsonNoJson() {
        String result = service.extractJson("This response contains no JSON at all.");
        assertNull(result);
    }

    @Test
    void extractJsonEmptyObject() {
        String result = service.extractJson("{}");
        assertNotNull(result);
        assertEquals("{}", result);
    }

    @Test
    void extractJsonNestedObjects() {
        String input = "{\"tags\": [\"a\", \"b\"], \"metadata\": {\"confidence\": 0.9}}";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.contains("\"confidence\""));
    }

    @Test
    void extractJsonWithWhitespace() {
        String input = "  \n  ```json\n  {\"tags\": [\"test\"]}  \n```  \n  ";
        String result = service.extractJson(input);
        assertNotNull(result);
        assertTrue(result.contains("\"test\""));
    }

    // --- getMediaType ---

    @Test
    void getMediaTypeJpg() {
        assertEquals("image/jpeg", service.getMediaType("photo.jpg"));
    }

    @Test
    void getMediaTypeJpeg() {
        assertEquals("image/jpeg", service.getMediaType("photo.jpeg"));
    }

    @Test
    void getMediaTypeJpgUpperCase() {
        assertEquals("image/jpeg", service.getMediaType("PHOTO.JPG"));
    }

    @Test
    void getMediaTypePng() {
        assertEquals("image/png", service.getMediaType("image.png"));
    }

    @Test
    void getMediaTypeGif() {
        assertEquals("image/gif", service.getMediaType("animation.gif"));
    }

    @Test
    void getMediaTypeWebp() {
        assertEquals("image/webp", service.getMediaType("photo.webp"));
    }

    @Test
    void getMediaTypeUnsupported() {
        assertNull(service.getMediaType("image.tiff"));
    }

    @Test
    void getMediaTypeRaw() {
        assertNull(service.getMediaType("photo.cr2"));
    }

    @Test
    void getMediaTypeWithPath() {
        assertEquals("image/jpeg", service.getMediaType("/home/user/photos/vacation.jpg"));
    }

    // --- AnalysisResult ---

    @Test
    void analysisResultDefaults() {
        ImageAnalysisService.AnalysisResult result = new ImageAnalysisService.AnalysisResult();
        assertNotNull(result.getTags());
        assertTrue(result.getTags().isEmpty());
        assertNotNull(result.getPersons());
        assertTrue(result.getPersons().isEmpty());
        assertNull(result.getPlace());
        assertNull(result.getRating());
        assertNull(result.getError());
        assertFalse(result.hasError());
        assertEquals(0, result.getInputTokens());
        assertEquals(0, result.getOutputTokens());
        assertEquals(0, result.getTotalTokens());
    }

    @Test
    void analysisResultHasError() {
        ImageAnalysisService.AnalysisResult result = new ImageAnalysisService.AnalysisResult();
        assertFalse(result.hasError());

        result.setError("something went wrong");
        assertTrue(result.hasError());

        result.setError("");
        assertFalse(result.hasError());

        result.setError(null);
        assertFalse(result.hasError());
    }

    @Test
    void analysisResultTokenCounting() {
        ImageAnalysisService.AnalysisResult result = new ImageAnalysisService.AnalysisResult();
        result.setInputTokens(1000);
        result.setOutputTokens(250);
        assertEquals(1250, result.getTotalTokens());
    }

    @Test
    void analysisResultSettersAndGetters() {
        ImageAnalysisService.AnalysisResult result = new ImageAnalysisService.AnalysisResult();
        result.setTags(List.of("landscape", "sunset"));
        result.setPersons(List.of("John", "Jane"));
        result.setPlace("Central Park");
        result.setRating("****");

        assertEquals(2, result.getTags().size());
        assertEquals("landscape", result.getTags().get(0));
        assertEquals(2, result.getPersons().size());
        assertEquals("John", result.getPersons().get(0));
        assertEquals("Central Park", result.getPlace());
        assertEquals("****", result.getRating());
    }
}
