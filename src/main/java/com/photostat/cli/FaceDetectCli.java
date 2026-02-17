package com.photostat.cli;

import com.photostat.models.FaceCluster;
import com.photostat.models.FaceDetection;
import com.photostat.services.ConfigService;
import com.photostat.services.FaceRecognitionService;
import com.photostat.services.OpenSearchService;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-line interface for batch face detection and clustering.
 * Runs without GUI. Supports parallel Python workers and incremental scanning.
 */
public class FaceDetectCli {

    private final ConfigService configService;
    private final FaceRecognitionService faceService;
    private final OpenSearchService openSearchService;

    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            ".jpg", ".jpeg", ".png"
    );

    private boolean dryRun = false;
    private boolean force = false;
    private boolean quiet = false;
    private boolean showProgress = true;
    private boolean clusterOnly = false;
    private boolean detectOnly = false;
    private String specificDir = null;
    private int parallelWorkers = 1;

    public FaceDetectCli() {
        this.configService = ConfigService.getInstance();
        this.faceService = FaceRecognitionService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
    }

    public int run(String[] args) {
        if (!parseArgs(args)) {
            return 1;
        }

        if (!quiet) {
            printConfig();
        }

        // Check Python availability
        if (!clusterOnly) {
            if (!quiet) {
                System.out.print("Checking Python environment... ");
            }
            if (!faceService.isPythonAvailable()) {
                System.err.println("FAILED");
                System.err.println("Error: Python with InsightFace not available.");
                System.err.println("Install with: pip install insightface onnxruntime");
                return 1;
            }
            if (!quiet) {
                String info = faceService.getPythonVersionInfo();
                boolean gpu = info.contains("\"gpu_available\": true");
                System.out.println("OK");
                System.out.println("Execution:   " + (gpu ? "GPU (CUDA)" : "CPU"));
                System.out.println("Python info: " + info);
            }
        }

        // Get image paths
        List<String> imagePaths;
        if (specificDir != null) {
            if (!quiet) {
                System.out.println("\nScanning directory: " + specificDir);
            }
            imagePaths = findImageFiles(specificDir);
        } else {
            // Use OpenSearch to get all indexed images
            if (!connectToOpenSearch()) {
                System.err.println("Error: Failed to connect to OpenSearch. Use --dir to scan a directory directly.");
                return 1;
            }
            if (!quiet) {
                System.out.println("\nFetching image paths from OpenSearch...");
            }
            try {
                List<String> allPaths = openSearchService.searchAllFilePaths(1000);
                // Filter to supported formats
                imagePaths = new ArrayList<>();
                for (String p : allPaths) {
                    String lower = p.toLowerCase();
                    for (String ext : SUPPORTED_FORMATS) {
                        if (lower.endsWith(ext)) {
                            imagePaths.add(p);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error fetching file paths: " + e.getMessage());
                return 1;
            }
        }

        if (imagePaths.isEmpty()) {
            System.out.println("No supported images found.");
            return 0;
        }

        // Show incremental scan info
        int alreadyScanned = 0;
        if (!force) {
            List<String> newPaths = faceService.filterNewPaths(imagePaths);
            alreadyScanned = imagePaths.size() - newPaths.size();
        }

        if (!quiet) {
            System.out.println("Found " + imagePaths.size() + " images" +
                    (alreadyScanned > 0 ? " (" + alreadyScanned + " already scanned)" : ""));
            if (force && alreadyScanned > 0) {
                System.out.println("Force mode: will rescan all images.");
            }
        }

        // Dry run
        if (dryRun) {
            int toScan = force ? imagePaths.size() : (imagePaths.size() - alreadyScanned);
            System.out.println("\n[DRY RUN] Would detect faces in " + toScan + " images" +
                    (parallelWorkers > 1 ? " using " + parallelWorkers + " parallel workers" : ""));
            System.out.println("Existing faces: " + faceService.getFaceDetections().size());
            System.out.println("Existing clusters: " + faceService.getClusters().size());
            return 0;
        }

        // Run detection
        if (!clusterOnly) {
            int result = runDetection(imagePaths);
            if (result != 0) return result;
        }

        // Run clustering
        if (!detectOnly) {
            int result = runClustering();
            if (result != 0) return result;
        }

        // Print summary
        if (!quiet) {
            System.out.println();
            System.out.println("=====================================");
            System.out.println("Face Detection Complete");
            System.out.println("=====================================");
            System.out.println("Total faces:    " + faceService.getFaceDetections().size());
            System.out.println("Total clusters: " + faceService.getClusters().size());

            long namedClusters = faceService.getClusters().stream()
                    .filter(c -> c.getPersonName() != null && !c.getPersonName().isEmpty())
                    .count();
            System.out.println("Named clusters: " + namedClusters);
            System.out.println("Scanned images: " + faceService.getScannedCount());
        }

        return 0;
    }

    private int runDetection(List<String> imagePaths) {
        long startTime = System.currentTimeMillis();

        if (!quiet) {
            int toScan = force ? imagePaths.size() : faceService.filterNewPaths(imagePaths).size();
            System.out.println("\nDetecting faces in " + toScan + " images" +
                    (parallelWorkers > 1 ? " (" + parallelWorkers + " parallel workers)" : "") + "...\n");
        }

        AtomicLong lastProgressTime = new AtomicLong(0);

        try {
            List<FaceDetection> newFaces;

            if (parallelWorkers > 1) {
                newFaces = faceService.detectFacesParallel(imagePaths, parallelWorkers,
                        (current, total) -> {
                            if (showProgress) {
                                long now = System.currentTimeMillis();
                                if (now - lastProgressTime.get() >= 500) {
                                    lastProgressTime.set(now);
                                    synchronized (System.out) {
                                        System.out.printf("\r[%d/%d] Detecting faces... %.0f%%",
                                                current, total, (current * 100.0) / total);
                                        System.out.flush();
                                    }
                                }
                            }
                        }, force);
            } else {
                newFaces = faceService.detectFacesBatch(imagePaths,
                        progress -> {
                            if (showProgress) {
                                long now = System.currentTimeMillis();
                                if (now - lastProgressTime.get() >= 500) {
                                    lastProgressTime.set(now);
                                    synchronized (System.out) {
                                        System.out.printf("\r[%.0f%%] Detecting faces...", progress * 100);
                                        System.out.flush();
                                    }
                                }
                            }
                        }, force);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            if (!quiet) {
                System.out.println();
                System.out.printf("Detection complete: %d new faces found in %.1f seconds%n",
                        newFaces.size(), elapsed / 1000.0);
            }
        } catch (Exception e) {
            System.err.println("\nError during face detection: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private int runClustering() {
        if (faceService.getFaceDetections().isEmpty()) {
            if (!quiet) {
                System.out.println("No faces to cluster.");
            }
            return 0;
        }

        if (!quiet) {
            System.out.println("\nClustering " + faceService.getFaceDetections().size() + " faces...");
        }

        long startTime = System.currentTimeMillis();

        try {
            List<FaceCluster> clusters = faceService.clusterFaces();
            long elapsed = System.currentTimeMillis() - startTime;

            if (!quiet) {
                System.out.printf("Clustering complete: %d clusters in %.1f seconds%n",
                        clusters.size(), elapsed / 1000.0);

                // Show top clusters
                int shown = 0;
                for (FaceCluster cluster : clusters) {
                    if (shown >= 10) {
                        System.out.println("  ... and " + (clusters.size() - 10) + " more clusters");
                        break;
                    }
                    System.out.printf("  Cluster %s: %d faces, %d photos%s%n",
                            cluster.getClusterId(),
                            cluster.getFaceIds().size(),
                            cluster.getPhotoCount(),
                            cluster.getPersonName() != null ? " [" + cluster.getPersonName() + "]" : "");
                    shown++;
                }
            }
        } catch (Exception e) {
            System.err.println("Error during clustering: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--detect-faces":
                    break;
                case "--dir":
                    if (i + 1 < args.length) {
                        specificDir = args[++i];
                    } else {
                        System.err.println("Error: --dir requires a path argument");
                        printUsage();
                        return false;
                    }
                    break;
                case "--parallel":
                    if (i + 1 < args.length) {
                        try {
                            parallelWorkers = Integer.parseInt(args[++i]);
                            if (parallelWorkers < 1 || parallelWorkers > 8) {
                                System.err.println("Error: --parallel must be between 1 and 8");
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Error: --parallel requires a number");
                            return false;
                        }
                    } else {
                        System.err.println("Error: --parallel requires a number (1-8)");
                        printUsage();
                        return false;
                    }
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--force":
                    force = true;
                    break;
                case "--cluster-only":
                    clusterOnly = true;
                    break;
                case "--detect-only":
                    detectOnly = true;
                    break;
                case "--quiet":
                case "-q":
                    quiet = true;
                    showProgress = false;
                    break;
                case "--no-progress":
                    showProgress = false;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return false;
                default:
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        printUsage();
                        return false;
                    }
                    break;
            }
        }
        return true;
    }

    private void printUsage() {
        System.out.println("PhotoStat CLI - Face Detection & Clustering");
        System.out.println();
        System.out.println("Usage: java -jar photostat.jar --detect-faces [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dir <path>       Scan specific directory (instead of OpenSearch index)");
        System.out.println("  --parallel <n>     Run n parallel Python workers (1-8, default: 1)");
        System.out.println("  --dry-run          Show what would be done without processing");
        System.out.println("  --force            Rescan all images, ignoring previous results");
        System.out.println("  --detect-only      Run detection only, skip clustering");
        System.out.println("  --cluster-only     Run clustering only on existing face data");
        System.out.println("  --quiet, -q        Minimal output");
        System.out.println("  --no-progress      Disable progress updates");
        System.out.println("  --help, -h         Show this help");
        System.out.println();
        System.out.println("Prerequisites:");
        System.out.println("  pip install insightface onnxruntime       (CPU)");
        System.out.println("  pip install insightface onnxruntime-gpu   (GPU, much faster)");
        System.out.println("  pip install scikit-learn                  (recommended for clustering)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar photostat.jar --detect-faces");
        System.out.println("  java -jar photostat.jar --detect-faces --parallel 4");
        System.out.println("  java -jar photostat.jar --detect-faces --dir /path/to/photos");
        System.out.println("  java -jar photostat.jar --detect-faces --cluster-only");
        System.out.println("  java -jar photostat.jar --detect-faces --dry-run");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - Scanning is incremental: only new images are processed on re-runs");
        System.out.println("  - Progress is saved every 500 images; safe to interrupt and resume");
        System.out.println("  - The InsightFace model (~350MB) auto-downloads on first run");
        System.out.println("  - With --parallel, each worker loads its own model (more RAM needed)");
    }

    private void printConfig() {
        System.out.println("PhotoStat CLI - Face Detection");
        System.out.println("=====================================");
        System.out.println("Python:      " + configService.getFacesPythonPath());
        System.out.println("Confidence:  " + configService.getFacesConfidenceThreshold());
        System.out.println("Cluster:     " + configService.getFacesClusterThreshold());
        System.out.println("Workers:     " + parallelWorkers);
        System.out.println("Config:      " + configService.getConfigPath());
    }

    private boolean connectToOpenSearch() {
        try {
            openSearchService.connect();
            return openSearchService.testConnection();
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> findImageFiles(String dirPath) {
        List<String> files = new ArrayList<>();
        Path dir = Paths.get(dirPath);

        if (!Files.exists(dir)) {
            System.err.println("Error: Directory not found: " + dirPath);
            return files;
        }

        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    for (String ext : SUPPORTED_FORMATS) {
                        if (name.endsWith(ext)) {
                            files.add(file.toAbsolutePath().toString());
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error scanning directory: " + e.getMessage());
        }

        return files;
    }
}
