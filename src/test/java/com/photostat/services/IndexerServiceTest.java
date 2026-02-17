package com.photostat.services;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IndexerService file collection and utility methods.
 */
public class IndexerServiceTest {

    private static IndexerService service;

    @BeforeAll
    static void setUp() {
        service = IndexerService.getInstance();
    }

    // --- getFileExtension ---

    @Test
    void getFileExtensionJpeg() {
        assertEquals(".jpg", service.getFileExtension("photo.jpg"));
    }

    @Test
    void getFileExtensionUpperCase() {
        assertEquals(".CR2", service.getFileExtension("IMG_1234.CR2"));
    }

    @Test
    void getFileExtensionMultipleDots() {
        assertEquals(".json", service.getFileExtension("photo.jpg.photostat.json"));
    }

    @Test
    void getFileExtensionNone() {
        assertEquals("", service.getFileExtension("README"));
    }

    @Test
    void getFileExtensionDotFile() {
        // Dotfiles like ".gitignore" — lastDot is 0, so returns ""
        assertEquals("", service.getFileExtension(".gitignore"));
    }

    // --- collectFiles ---

    @Test
    void collectFilesFindsMatchingExtensions(@TempDir Path tempDir) throws IOException {
        // Create test files
        Files.createFile(tempDir.resolve("photo1.jpg"));
        Files.createFile(tempDir.resolve("photo2.png"));
        Files.createFile(tempDir.resolve("readme.txt"));
        Files.createFile(tempDir.resolve("document.pdf"));

        Set<String> extensions = Set.of(".jpg", ".png");
        List<Path> result = service.collectFiles(List.of(tempDir.toString()), extensions);

        assertEquals(2, result.size());

        List<String> fileNames = result.stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        assertEquals(List.of("photo1.jpg", "photo2.png"), fileNames);
    }

    @Test
    void collectFilesIgnoresNonImageFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("notes.txt"));
        Files.createFile(tempDir.resolve("data.csv"));
        Files.createFile(tempDir.resolve("script.py"));

        Set<String> extensions = Set.of(".jpg", ".png", ".tiff");
        List<Path> result = service.collectFiles(List.of(tempDir.toString()), extensions);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectFilesHandlesEmptyDirectory(@TempDir Path tempDir) throws IOException {
        Set<String> extensions = Set.of(".jpg");
        List<Path> result = service.collectFiles(List.of(tempDir.toString()), extensions);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectFilesHandlesNonExistentDirectory() throws IOException {
        Set<String> extensions = Set.of(".jpg");
        List<Path> result = service.collectFiles(List.of("/nonexistent/path/12345"), extensions);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectFilesWalksSubdirectories(@TempDir Path tempDir) throws IOException {
        // Create nested directory structure
        Path subDir = Files.createDirectories(tempDir.resolve("vacation").resolve("day1"));
        Files.createFile(tempDir.resolve("root.jpg"));
        Files.createFile(subDir.resolve("nested.jpg"));
        Files.createFile(subDir.resolve("skip.txt"));

        Set<String> extensions = Set.of(".jpg");
        List<Path> result = service.collectFiles(List.of(tempDir.toString()), extensions);

        assertEquals(2, result.size());
    }

    @Test
    void collectFilesMultipleDirectories(@TempDir Path tempDir) throws IOException {
        Path dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        Path dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        Files.createFile(dir1.resolve("a.jpg"));
        Files.createFile(dir2.resolve("b.jpg"));
        Files.createFile(dir2.resolve("c.png"));

        Set<String> extensions = Set.of(".jpg", ".png");
        List<Path> result = service.collectFiles(List.of(dir1.toString(), dir2.toString()), extensions);

        assertEquals(3, result.size());
    }

    // --- IndexingStats ---

    @Test
    void indexingStatsDefaults() {
        IndexerService.IndexingStats stats = new IndexerService.IndexingStats();
        assertEquals(0, stats.totalFiles.get());
        assertEquals(0, stats.processedFiles.get());
        assertEquals(0, stats.indexedFiles.get());
        assertEquals(0, stats.skippedFiles.get());
        assertEquals(0, stats.errorFiles.get());
    }

    @Test
    void indexingStatsToString() {
        IndexerService.IndexingStats stats = new IndexerService.IndexingStats();
        stats.totalFiles.set(100);
        stats.processedFiles.set(80);
        stats.indexedFiles.set(75);
        stats.skippedFiles.set(15);
        stats.errorFiles.set(5);

        String str = stats.toString();
        assertTrue(str.contains("100"));
        assertTrue(str.contains("80"));
        assertTrue(str.contains("75"));
        assertTrue(str.contains("15"));
        assertTrue(str.contains("5"));
    }
}
