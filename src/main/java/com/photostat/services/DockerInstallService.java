package com.photostat.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Detects and installs Docker Desktop on the user's behalf.
 *
 * <p>PhotoStat never bundles or redistributes Docker. Where a platform package
 * manager is available the install is delegated to it, so the bits still come
 * from Docker's own servers; otherwise the user is sent to the download page.
 *
 * <p>Installing Docker Desktop is a machine-wide change — it enables WSL 2 on
 * Windows, installs a service running as SYSTEM, and adds the user to the
 * {@code docker-users} group — so callers must obtain explicit, informed
 * consent first. Nothing here reboots the machine; when a restart is needed the
 * user is told and left to choose the moment.
 */
public class DockerInstallService {

    /** Official download page, used whenever an automated install is unavailable. */
    public static final String DOWNLOAD_URL = "https://www.docker.com/products/docker-desktop/";

    /** Docker's licence terms, shown before any install is offered. */
    public static final String LICENCE_URL = "https://docs.docker.com/subscription/desktop-license/";

    private static DockerInstallService instance;

    private final LoggingService logger;

    private DockerInstallService() {
        this.logger = LoggingService.getInstance();
    }

    public static synchronized DockerInstallService getInstance() {
        if (instance == null) {
            instance = new DockerInstallService();
        }
        return instance;
    }

    /** How Docker Desktop can be installed on this machine. */
    public enum InstallMethod {
        /** Windows Package Manager, present on current Windows 10 and 11. */
        WINGET,
        /** Homebrew cask on macOS. */
        HOMEBREW,
        /** No supported package manager; the user must download it themselves. */
        MANUAL
    }

    // ------------------------------------------------------------------
    // Platform
    // ------------------------------------------------------------------

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    public boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    public boolean isMac() {
        return isMac(System.getProperty("os.name", ""));
    }

    public boolean isLinux() {
        return !isWindows() && !isMac();
    }

    // ------------------------------------------------------------------
    // Capability detection
    // ------------------------------------------------------------------

    /**
     * Which install route is available here.
     *
     * <p>Linux always returns {@link InstallMethod#MANUAL}: Docker Engine comes
     * from the distro package manager and needs root, which is not something to
     * do behind a desktop app's consent dialog.
     */
    public InstallMethod detectInstallMethod() {
        if (isWindows() && commandSucceeds(List.of("winget", "--version"))) {
            return InstallMethod.WINGET;
        }
        if (isMac() && findBrew() != null) {
            return InstallMethod.HOMEBREW;
        }
        return InstallMethod.MANUAL;
    }

    /**
     * Locate the Homebrew executable, or null when it is not installed.
     *
     * <p>A macOS app launched from Finder gets only the minimal system PATH, so
     * a bare {@code brew} is not found even when Homebrew is installed — hence
     * the explicit locations. Apple Silicon installs to {@code /opt/homebrew},
     * Intel to {@code /usr/local}.
     */
    String findBrew() {
        if (commandSucceeds(List.of("brew", "--version"))) {
            return "brew";
        }
        for (String candidate : List.of("/opt/homebrew/bin/brew", "/usr/local/bin/brew")) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The command that would install Docker Desktop, or null for
     * {@link InstallMethod#MANUAL}.
     *
     * <p>Static and parameterised so it can be asserted in tests on any platform.
     */
    static List<String> buildInstallCommand(InstallMethod method) {
        switch (method) {
            case WINGET:
                // -e forces an exact id match; the accept flags stop winget
                // blocking on interactive agreement prompts.
                return List.of("winget", "install", "--id", "Docker.DockerDesktop", "-e",
                        "--accept-package-agreements", "--accept-source-agreements");
            case HOMEBREW:
                return List.of("brew", "install", "--cask", "docker");
            default:
                return null;
        }
    }

    /**
     * Virtualisation products that are known to conflict with the Hyper-V layer
     * WSL 2 requires. Returns display names, empty when none are found.
     *
     * <p>These do not block installation — modern versions of both coexist with
     * Hyper-V — but performance degrades and older versions break outright, so
     * the user deserves to know before enabling WSL 2.
     */
    public List<String> detectHypervisorConflicts() {
        List<String> conflicts = new ArrayList<>();
        if (!isWindows()) {
            return conflicts;
        }
        if (anyPathExists(
                "C:\\Program Files\\Oracle\\VirtualBox\\VBoxManage.exe",
                System.getenv("ProgramFiles") + "\\Oracle\\VirtualBox\\VBoxManage.exe")) {
            conflicts.add("Oracle VirtualBox");
        }
        if (anyPathExists(
                "C:\\Program Files (x86)\\VMware\\VMware Workstation\\vmware.exe",
                "C:\\Program Files\\VMware\\VMware Workstation\\vmware.exe")) {
            conflicts.add("VMware Workstation");
        }
        return conflicts;
    }

    /** What installing Docker Desktop changes, shown verbatim in the consent step. */
    public List<String> describeSystemChanges() {
        List<String> changes = new ArrayList<>();
        if (isWindows()) {
            changes.add("Enables the WSL 2 and Virtual Machine Platform Windows features, "
                    + "which run Windows on top of a hypervisor.");
            changes.add("Installs a background service that runs as SYSTEM.");
            changes.add("Adds your account to the 'docker-users' group. Membership grants "
                    + "control of the Docker engine, which is equivalent to administrator access.");
            changes.add("Starts Docker Desktop automatically when you log in (you can turn "
                    + "this off in Docker Desktop's own settings).");
            changes.add("May require a restart to finish. PhotoStat will not restart your "
                    + "machine — you choose when.");
        } else if (isMac()) {
            changes.add("Installs Docker Desktop into /Applications.");
            changes.add("Installs a privileged helper used to manage the Docker engine.");
            changes.add("Starts Docker Desktop automatically when you log in (you can turn "
                    + "this off in Docker Desktop's own settings).");
        }
        changes.add("Docker Desktop requires a paid subscription for commercial use in "
                + "organisations above Docker's size threshold. Review the licence terms "
                + "before installing at work.");
        return changes;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Run the platform install command, streaming output to the callback.
     *
     * <p>Blocking, and expected to trigger an elevation prompt the user must
     * accept. Returns null on success or a message on failure.
     */
    public String installDockerDesktop(Consumer<String> log) {
        InstallMethod method = detectInstallMethod();
        List<String> command = buildInstallCommand(method);
        if (command == null) {
            return "No supported package manager was found. Download Docker Desktop from "
                    + DOWNLOAD_URL;
        }
        if (method == InstallMethod.HOMEBREW) {
            // Use the resolved location, since a bare "brew" is not on the PATH
            // of an app launched from Finder.
            String brew = findBrew();
            if (brew != null && !"brew".equals(brew)) {
                command = new ArrayList<>(command);
                command.set(0, brew);
            }
        }

        logger.info("DockerInstallService", "Running: " + String.join(" ", command));
        emit(log, "Running: " + String.join(" ", command));
        emit(log, "Accept the elevation prompt if your system asks for it.");

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        emit(log, line.trim());
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                emit(log, "Docker Desktop installed.");
                return null;
            }
            String error = command.get(0) + " exited with code " + exitCode
                    + ". Install Docker Desktop manually from " + DOWNLOAD_URL;
            emit(log, error);
            return error;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Installation was interrupted.";
        } catch (IOException e) {
            String error = "Could not run " + command.get(0) + ": " + e.getMessage();
            emit(log, error);
            return error;
        }
    }

    /** Open a URL in the user's browser. Returns null on success, or a message. */
    public String openInBrowser(String url) {
        List<String> command;
        if (isWindows()) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (isMac()) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(command).start();
            return null;
        } catch (IOException e) {
            return "Could not open " + url + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean anyPathExists(String... paths) {
        for (String path : paths) {
            if (path != null && Files.exists(Paths.get(path))) {
                return true;
            }
        }
        return false;
    }

    /** True when the command runs and exits zero. Used only for capability probes. */
    private boolean commandSucceeds(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void emit(Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
