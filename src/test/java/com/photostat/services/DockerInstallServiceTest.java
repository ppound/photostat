package com.photostat.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DockerInstallService command assembly and platform detection.
 *
 * <p>Covers the pure helpers, so they run on any platform regardless of whether
 * winget, Homebrew or Docker are present.
 */
public class DockerInstallServiceTest {

    // --- buildInstallCommand ---

    @Test
    void wingetCommandTargetsDockerDesktopExactly() {
        List<String> command =
                DockerInstallService.buildInstallCommand(DockerInstallService.InstallMethod.WINGET);

        assertEquals("winget", command.get(0));
        assertTrue(command.contains("Docker.DockerDesktop"));
        // -e pins an exact id match, so a similarly named package cannot be installed.
        assertTrue(command.contains("-e"), "must require an exact package id match");
        // Without these winget blocks forever on interactive agreement prompts.
        assertTrue(command.contains("--accept-package-agreements"));
        assertTrue(command.contains("--accept-source-agreements"));
    }

    @Test
    void homebrewCommandInstallsTheCask() {
        List<String> command =
                DockerInstallService.buildInstallCommand(DockerInstallService.InstallMethod.HOMEBREW);

        assertEquals(List.of("brew", "install", "--cask", "docker"), command);
    }

    @Test
    void manualMethodHasNoCommand() {
        assertNull(DockerInstallService.buildInstallCommand(DockerInstallService.InstallMethod.MANUAL));
    }

    @Test
    void installCommandNeverElevatesOrReboots() {
        for (DockerInstallService.InstallMethod method : DockerInstallService.InstallMethod.values()) {
            List<String> command = DockerInstallService.buildInstallCommand(method);
            if (command == null) {
                continue;
            }
            String joined = String.join(" ", command).toLowerCase();
            assertFalse(joined.contains("shutdown"), "must never reboot the machine");
            assertFalse(joined.contains("/restart"), "must never reboot the machine");
            assertFalse(joined.contains("sudo"), "must not silently elevate");
        }
    }

    // --- platform detection ---

    @Test
    void detectsWindowsRegardlessOfVersionString() {
        assertTrue(DockerInstallService.isWindows("Windows 11"));
        assertTrue(DockerInstallService.isWindows("Windows 10"));
        assertFalse(DockerInstallService.isWindows("Linux"));
        assertFalse(DockerInstallService.isWindows("Mac OS X"));
        assertFalse(DockerInstallService.isWindows(null));
    }

    @Test
    void detectsMacRegardlessOfVersionString() {
        assertTrue(DockerInstallService.isMac("Mac OS X"));
        assertTrue(DockerInstallService.isMac("macOS"));
        assertFalse(DockerInstallService.isMac("Linux"));
        assertFalse(DockerInstallService.isMac("Windows 11"));
        assertFalse(DockerInstallService.isMac(null));
    }

    // --- consent copy ---

    @Test
    void systemChangesAlwaysMentionLicensingAndAreNonEmpty() {
        List<String> changes = DockerInstallService.getInstance().describeSystemChanges();

        assertFalse(changes.isEmpty(), "the consent step must describe what changes");
        String joined = String.join(" ", changes).toLowerCase();
        // The licence warning applies on every platform, since it is about the
        // user's organisation rather than their OS.
        assertTrue(joined.contains("licence") || joined.contains("license"),
                "consent copy must mention Docker Desktop licensing");
    }

    @Test
    void downloadAndLicenceUrlsPointAtDocker() {
        assertTrue(DockerInstallService.DOWNLOAD_URL.startsWith("https://"));
        assertTrue(DockerInstallService.DOWNLOAD_URL.contains("docker.com"));
        assertTrue(DockerInstallService.LICENCE_URL.startsWith("https://"));
        assertTrue(DockerInstallService.LICENCE_URL.contains("docker.com"));
    }

    @Test
    void hypervisorConflictDetectionIsSafeOnAnyPlatform() {
        // Must not throw on Linux/macOS, where the Windows paths do not exist.
        assertNotNull(DockerInstallService.getInstance().detectHypervisorConflicts());
    }
}
