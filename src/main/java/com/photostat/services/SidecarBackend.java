package com.photostat.services;

import java.nio.file.Path;

/**
 * A storage backend for sidecar metadata. Implementations read/write a single
 * on-disk representation (JSON or XMP) of {@link SidecarService.SidecarData}.
 *
 * <p>Merge logic (preserving analysisHash, cloudUploads across writes, and
 * deleting empty sidecars) lives in {@link SidecarService} so it is not
 * duplicated in each backend.
 */
interface SidecarBackend {

    /**
     * Get the path where this backend would store a sidecar for the given image.
     */
    Path getSidecarPath(String imagePath);

    /**
     * Check whether a sidecar file exists for the given image.
     */
    boolean exists(String imagePath);

    /**
     * Read the full sidecar data from disk.
     *
     * @return the data, or {@code null} if no sidecar exists or cannot be parsed.
     */
    SidecarService.SidecarData read(String imagePath);

    /**
     * Write the full sidecar data to disk, replacing any existing file.
     * Callers are responsible for merging with existing data first.
     *
     * @return {@code true} on success.
     */
    boolean write(String imagePath, SidecarService.SidecarData data);

    /**
     * Delete the sidecar file for the given image if it exists.
     *
     * @return {@code true} on success (including when the file did not exist).
     */
    boolean delete(String imagePath);
}
