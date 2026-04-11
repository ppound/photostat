package com.photostat.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link XmpSidecarBackend}: round-trip serialization for each
 * supported field, and empty-read behaviour.
 */
public class XmpSidecarBackendTest {

    private final XmpSidecarBackend backend = new XmpSidecarBackend();

    @Test
    void readReturnsNullWhenSidecarMissing(@TempDir Path tmp) {
        Path image = tmp.resolve("nonexistent.jpg");
        assertNull(backend.read(image.toString()));
        assertFalse(backend.exists(image.toString()));
    }

    @Test
    void sidecarPathAppendsXmpExtension(@TempDir Path tmp) {
        Path image = tmp.resolve("photo.jpg");
        assertEquals(image.toString() + ".xmp", backend.getSidecarPath(image.toString()).toString());
    }

    @Test
    void roundTripAllFields(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.SidecarData original = new SidecarService.SidecarData();
        original.setRating("4");
        original.setTags(List.of("Sunset", "Landscape", "Golden Hour"));
        original.setPersons(List.of("woman in red dress", "elderly man"));
        original.setPlace("Paris");
        original.setAnalysisHash("abc123def456");
        original.setCloudUploads(List.of("s3-backup", "backblaze"));

        assertTrue(backend.write(image.toString(), original));
        assertTrue(backend.exists(image.toString()));

        SidecarService.SidecarData read = backend.read(image.toString());
        assertNotNull(read);
        assertEquals("4", read.getRating());
        assertEquals(List.of("Sunset", "Landscape", "Golden Hour"), read.getTags());
        assertEquals(List.of("woman in red dress", "elderly man"), read.getPersons());
        assertEquals("Paris", read.getPlace());
        assertEquals("abc123def456", read.getAnalysisHash());
        assertEquals(List.of("s3-backup", "backblaze"), read.getCloudUploads());
    }

    @Test
    void placeAcceptsNonGeographicValues(@TempDir Path tmp) throws Exception {
        // PhotoStat's place field is free-form and may hold values like
        // "Restaurant" or "Beach" that would not be valid in a standard
        // IPTC City field. Verify the custom photostat:place field round-trips.
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        for (String place : List.of("Restaurant", "Beach", "Grandma's House", "On the moon")) {
            SidecarService.SidecarData original = new SidecarService.SidecarData();
            original.setPlace(place);
            assertTrue(backend.write(image.toString(), original));

            SidecarService.SidecarData read = backend.read(image.toString());
            assertNotNull(read);
            assertEquals(place, read.getPlace(), "round-trip failed for: " + place);
        }
    }

    @Test
    void roundTripPartialFields(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.SidecarData original = new SidecarService.SidecarData();
        original.setRating("5");
        original.setTags(List.of("Portrait"));
        // place, persons, analysisHash, cloudUploads intentionally null

        assertTrue(backend.write(image.toString(), original));
        SidecarService.SidecarData read = backend.read(image.toString());
        assertNotNull(read);
        assertEquals("5", read.getRating());
        assertEquals(List.of("Portrait"), read.getTags());
        assertNull(read.getPlace());
        assertNull(read.getPersons());
        assertNull(read.getAnalysisHash());
        assertNull(read.getCloudUploads());
    }

    @Test
    void writtenFileIsValidXmpPacket(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.SidecarData data = new SidecarService.SidecarData();
        data.setRating("3");
        assertTrue(backend.write(image.toString(), data));

        String xmp = Files.readString(backend.getSidecarPath(image.toString()));
        // Sanity-check the serialized XML contains the expected XMP markers
        assertTrue(xmp.contains("<x:xmpmeta"), "expected x:xmpmeta wrapper");
        assertTrue(xmp.contains("xmp:Rating") || xmp.contains("Rating"),
                "expected Rating property in XMP output");
    }

    @Test
    void deleteRemovesFile(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.SidecarData data = new SidecarService.SidecarData();
        data.setRating("2");
        backend.write(image.toString(), data);
        assertTrue(backend.exists(image.toString()));

        assertTrue(backend.delete(image.toString()));
        assertFalse(backend.exists(image.toString()));
    }

    @Test
    void deleteOnMissingFileIsNoOp(@TempDir Path tmp) {
        Path image = tmp.resolve("never-existed.jpg");
        assertTrue(backend.delete(image.toString()));
    }
}
