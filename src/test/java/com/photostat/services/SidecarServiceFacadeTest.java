package com.photostat.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SidecarService}'s facade behaviour: format selection,
 * read-both fallback, and "both" mode dual-writes.
 *
 * <p>These tests mutate the process-global {@link ConfigService} singleton's
 * in-memory state (not the on-disk file) and restore it in {@code @AfterEach}.
 */
public class SidecarServiceFacadeTest {

    private SidecarService service;
    private String savedFormat;
    private boolean savedReadBoth;

    @BeforeEach
    void snapshotConfig() {
        service = SidecarService.getInstance();
        savedFormat = ConfigService.getInstance().getSidecarFormat();
        savedReadBoth = ConfigService.getInstance().isSidecarReadBoth();
    }

    @AfterEach
    void restoreConfig() {
        ConfigService.getInstance().setSidecarFormat(savedFormat);
        ConfigService.getInstance().setSidecarReadBoth(savedReadBoth);
    }

    // --- primary format selection ------------------------------------------

    @Test
    void jsonModeWritesOnlyJsonSidecar(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("Tag1"), "****"));

        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertTrue(Files.exists(jsonPath), "JSON sidecar should exist");
        assertFalse(Files.exists(xmpPath), "XMP sidecar should not exist");
    }

    @Test
    void xmpModeWritesOnlyXmpSidecar(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("Tag1"), "****"));

        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertFalse(Files.exists(jsonPath), "JSON sidecar should not exist");
        assertTrue(Files.exists(xmpPath), "XMP sidecar should exist");
    }

    @Test
    void bothModeWritesBothSidecars(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("both");
        ConfigService.getInstance().setSidecarReadBoth(true);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        assertTrue(service.writeSidecar(image.toString(), List.of("alice"), "NYC",
                List.of("Tag1", "Tag2"), "*****"));

        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertTrue(Files.exists(jsonPath));
        assertTrue(Files.exists(xmpPath));
    }

    // --- read-both fallback ------------------------------------------------

    @Test
    void readBothFallsBackFromJsonToXmp(@TempDir Path tmp) throws Exception {
        // Write as XMP first
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, "Tokyo", List.of("travel"), "*****");

        // Now switch primary to JSON with read-both enabled → should still read XMP
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(true);

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data, "should fall back to XMP sidecar");
        assertEquals("*****", data.getRating());
        assertEquals("Tokyo", data.getPlace());
        assertEquals(List.of("travel"), data.getTags());
    }

    @Test
    void readBothFallsBackFromXmpToJson(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), List.of("bob"), null, null, "***");

        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(true);

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data, "should fall back to JSON sidecar");
        assertEquals("***", data.getRating());
        assertEquals(List.of("bob"), data.getPersons());
    }

    @Test
    void readBothDisabledDoesNotFallBack(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, null, List.of("a"), "*****");

        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        // Primary=json, no JSON file exists, readBoth disabled → null
        assertNull(service.readSidecar(image.toString()));
    }

    // --- merge behaviour ---------------------------------------------------

    @Test
    void writePreservesAnalysisHashAcrossPartialUpdates(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        // Seed an analysis hash
        assertTrue(service.updateAnalysisCache(image.toString(), "hash-abc"));

        // Now write user metadata — hash must survive
        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("x"), "****"));

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data);
        assertEquals("hash-abc", data.getAnalysisHash());
        assertEquals("****", data.getRating());
    }

    @Test
    void writePreservesCloudUploadsAcrossPartialUpdates(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        assertTrue(service.addCloudUpload(image.toString(), "s3-backup"));
        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("y"), "**"));

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data);
        assertEquals(List.of("s3-backup"), data.getCloudUploads());
        assertEquals("**", data.getRating());
    }

    // --- JSON → XMP conversion -------------------------------------------

    @Test
    void convertJsonToXmpFullFieldRoundTrip(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        // Seed a rich JSON sidecar covering every field, including analysisHash
        // and a cloud upload record.
        service.writeSidecar(image.toString(),
                List.of("alice", "bob"),
                "Restaurant",
                List.of("Food", "Evening"),
                "*****");
        service.updateAnalysisCache(image.toString(), "hash-xyz");
        service.addCloudUpload(image.toString(), "s3-backup");

        // Convert without deleting the JSON
        SidecarService.ConversionResult result =
                service.convertJsonToXmp(image.toString(), false);
        assertEquals(SidecarService.ConversionResult.CONVERTED, result);

        // JSON should still be there
        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertTrue(Files.exists(jsonPath));
        assertTrue(Files.exists(xmpPath));

        // Read back through the XMP backend directly to verify every field migrated
        XmpSidecarBackend xmp = new XmpSidecarBackend();
        SidecarService.SidecarData data = xmp.read(image.toString());
        assertNotNull(data);
        assertEquals(List.of("alice", "bob"), data.getPersons());
        assertEquals("Restaurant", data.getPlace());
        assertEquals(List.of("Food", "Evening"), data.getTags());
        assertEquals("*****", data.getRating());
        assertEquals("hash-xyz", data.getAnalysisHash());
        assertEquals(List.of("s3-backup"), data.getCloudUploads());
    }

    @Test
    void convertJsonToXmpDeletesJsonWhenFlagSet(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, null, List.of("tag"), "***");

        SidecarService.ConversionResult result =
                service.convertJsonToXmp(image.toString(), true);
        assertEquals(SidecarService.ConversionResult.CONVERTED, result);

        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertFalse(Files.exists(jsonPath), "JSON should be deleted after conversion");
        assertTrue(Files.exists(xmpPath));
    }

    @Test
    void convertJsonToXmpReturnsSkippedWhenNoJson(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        SidecarService.ConversionResult result =
                service.convertJsonToXmp(image.toString(), true);
        assertEquals(SidecarService.ConversionResult.SKIPPED_NO_JSON, result);
    }

    @Test
    void writeEmptyMetadataDeletesBothSidecars(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("both");
        ConfigService.getInstance().setSidecarReadBoth(true);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, null, List.of("a"), "*****");

        Path jsonPath = Path.of(image.toString() + JsonSidecarBackend.SIDECAR_EXTENSION);
        Path xmpPath = Path.of(image.toString() + XmpSidecarBackend.SIDECAR_EXTENSION);
        assertTrue(Files.exists(jsonPath));
        assertTrue(Files.exists(xmpPath));

        // Clear everything
        service.writeSidecar(image.toString(), null, null, null, null);

        assertFalse(Files.exists(jsonPath));
        assertFalse(Files.exists(xmpPath));
    }
}
