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

        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("Tag1"), "4"));

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

        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("Tag1"), "4"));

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
                List.of("Tag1", "Tag2"), "5"));

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
        service.writeSidecar(image.toString(), null, "Tokyo", List.of("travel"), "5");

        // Now switch primary to JSON with read-both enabled → should still read XMP
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(true);

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data, "should fall back to XMP sidecar");
        assertEquals("5", data.getRating());
        assertEquals("Tokyo", data.getPlace());
        assertEquals(List.of("travel"), data.getTags());
    }

    @Test
    void readBothFallsBackFromXmpToJson(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("json");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), List.of("bob"), null, null, "3");

        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(true);

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data, "should fall back to JSON sidecar");
        assertEquals("3", data.getRating());
        assertEquals(List.of("bob"), data.getPersons());
    }

    @Test
    void readBothDisabledDoesNotFallBack(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, null, List.of("a"), "5");

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
        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("x"), "4"));

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data);
        assertEquals("hash-abc", data.getAnalysisHash());
        assertEquals("4", data.getRating());
    }

    @Test
    void writePreservesCloudUploadsAcrossPartialUpdates(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("xmp");
        ConfigService.getInstance().setSidecarReadBoth(false);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);

        assertTrue(service.addCloudUpload(image.toString(), "s3-backup"));
        assertTrue(service.writeSidecar(image.toString(), null, null, List.of("y"), "2"));

        SidecarService.SidecarData data = service.readSidecar(image.toString());
        assertNotNull(data);
        assertEquals(List.of("s3-backup"), data.getCloudUploads());
        assertEquals("2", data.getRating());
    }

    @Test
    void writeEmptyMetadataDeletesBothSidecars(@TempDir Path tmp) throws Exception {
        ConfigService.getInstance().setSidecarFormat("both");
        ConfigService.getInstance().setSidecarReadBoth(true);

        Path image = tmp.resolve("photo.jpg");
        Files.createFile(image);
        service.writeSidecar(image.toString(), null, null, List.of("a"), "5");

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
