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
        // PhotoStat's canonical rating format is asterisks — not a named person
        // clustering concept. 4 stars round-trips via a numeric xmp:Rating.
        original.setRating("****");
        original.setTags(List.of("Sunset", "Landscape", "Golden Hour"));
        original.setPersons(List.of("Alice Smith", "Bob Jones"));
        original.setPlace("Paris");
        original.setAnalysisHash("abc123def456");
        original.setCloudUploads(List.of("s3-backup", "backblaze"));

        assertTrue(backend.write(image.toString(), original));
        assertTrue(backend.exists(image.toString()));

        SidecarService.SidecarData read = backend.read(image.toString());
        assertNotNull(read);
        assertEquals("****", read.getRating());
        assertEquals(List.of("Sunset", "Landscape", "Golden Hour"), read.getTags());
        assertEquals(List.of("Alice Smith", "Bob Jones"), read.getPersons());
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
        original.setRating("*****");
        original.setTags(List.of("Portrait"));
        // place, persons, analysisHash, cloudUploads intentionally null

        assertTrue(backend.write(image.toString(), original));
        SidecarService.SidecarData read = backend.read(image.toString());
        assertNotNull(read);
        assertEquals("*****", read.getRating());
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
        data.setRating("***");
        assertTrue(backend.write(image.toString(), data));

        String xmp = Files.readString(backend.getSidecarPath(image.toString()));
        // Sanity-check the serialized XML contains the expected XMP markers
        assertTrue(xmp.contains("<x:xmpmeta"), "expected x:xmpmeta wrapper");
        // xmp:Rating must be written as the numeric form required by the XMP
        // spec, not the literal asterisks PhotoStat uses internally.
        assertTrue(xmp.contains("xmp:Rating=\"3\"") || xmp.contains("<xmp:Rating>3</xmp:Rating>"),
                "expected xmp:Rating=3 in XMP output, got: " + xmp);
        assertFalse(xmp.contains("***"),
                "xmp:Rating should not contain asterisks (XMP spec requires numeric)");
    }

    @Test
    void deleteRemovesFile(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.SidecarData data = new SidecarService.SidecarData();
        data.setRating("**");
        backend.write(image.toString(), data);
        assertTrue(backend.exists(image.toString()));

        assertTrue(backend.delete(image.toString()));
        assertFalse(backend.exists(image.toString()));
    }

    // --- rating conversion -------------------------------------------------

    @Test
    void asterisksToNumberConvertsAllStarCounts() {
        assertEquals("1", XmpSidecarBackend.asterisksToNumber("*"));
        assertEquals("2", XmpSidecarBackend.asterisksToNumber("**"));
        assertEquals("3", XmpSidecarBackend.asterisksToNumber("***"));
        assertEquals("4", XmpSidecarBackend.asterisksToNumber("****"));
        assertEquals("5", XmpSidecarBackend.asterisksToNumber("*****"));
    }

    @Test
    void asterisksToNumberHandlesNullAndEmpty() {
        assertNull(XmpSidecarBackend.asterisksToNumber(null));
        assertNull(XmpSidecarBackend.asterisksToNumber(""));
        assertNull(XmpSidecarBackend.asterisksToNumber("   "));
    }

    @Test
    void asterisksToNumberAcceptsLegacyNumericForm() {
        // Some tests / older data may use bare numeric strings — accept them.
        assertEquals("1", XmpSidecarBackend.asterisksToNumber("1"));
        assertEquals("3", XmpSidecarBackend.asterisksToNumber("3"));
        assertEquals("5", XmpSidecarBackend.asterisksToNumber("5"));
    }

    @Test
    void asterisksToNumberRejectsOutOfRange() {
        assertNull(XmpSidecarBackend.asterisksToNumber("******"));    // 6 stars
        assertNull(XmpSidecarBackend.asterisksToNumber("0"));         // unrated
        assertNull(XmpSidecarBackend.asterisksToNumber("-1"));        // rejected
        assertNull(XmpSidecarBackend.asterisksToNumber("6"));         // out of range
        assertNull(XmpSidecarBackend.asterisksToNumber("garbage"));
    }

    @Test
    void numberToAsterisksConvertsAllStarCounts() {
        assertEquals("*", XmpSidecarBackend.numberToAsterisks("1"));
        assertEquals("**", XmpSidecarBackend.numberToAsterisks("2"));
        assertEquals("***", XmpSidecarBackend.numberToAsterisks("3"));
        assertEquals("****", XmpSidecarBackend.numberToAsterisks("4"));
        assertEquals("*****", XmpSidecarBackend.numberToAsterisks("5"));
    }

    @Test
    void numberToAsterisksAcceptsFloatFormFromLightroom() {
        // Lightroom often writes xmp:Rating as a float (e.g. "3.0")
        assertEquals("***", XmpSidecarBackend.numberToAsterisks("3.0"));
        assertEquals("****", XmpSidecarBackend.numberToAsterisks("4.0"));
        // Fractional values round to nearest integer
        assertEquals("***", XmpSidecarBackend.numberToAsterisks("2.7"));
    }

    @Test
    void numberToAsterisksTreatsZeroAndNegativeAsUnrated() {
        // xmp:Rating=0 means "not rated", -1 means "rejected" — PhotoStat
        // has no rejected concept, so both become unrated (null).
        assertNull(XmpSidecarBackend.numberToAsterisks("0"));
        assertNull(XmpSidecarBackend.numberToAsterisks("0.0"));
        assertNull(XmpSidecarBackend.numberToAsterisks("-1"));
    }

    @Test
    void numberToAsterisksHandlesNullAndGarbage() {
        assertNull(XmpSidecarBackend.numberToAsterisks(null));
        assertNull(XmpSidecarBackend.numberToAsterisks(""));
        assertNull(XmpSidecarBackend.numberToAsterisks("garbage"));
        assertNull(XmpSidecarBackend.numberToAsterisks("99"));
    }

    @Test
    void writtenXmpRatingCanBeReadByLightroomStyleFloatValue(@TempDir Path tmp) throws Exception {
        // Simulate an XMP file that a tool like Lightroom produced: xmp:Rating
        // written as a float. We should read it back as the equivalent asterisks.
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        Path sidecar = backend.getSidecarPath(image.toString());
        String xmp = """
                <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                      <xmp:Rating>4.0</xmp:Rating>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>
                """;
        Files.writeString(sidecar, xmp);

        SidecarService.SidecarData data = backend.read(image.toString());
        assertNotNull(data);
        assertEquals("****", data.getRating());
    }

    @Test
    void deleteOnMissingFileIsNoOp(@TempDir Path tmp) {
        Path image = tmp.resolve("never-existed.jpg");
        assertTrue(backend.delete(image.toString()));
    }
}
