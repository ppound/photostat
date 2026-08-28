package com.photostat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service for driving the PhotoStat backend containers via the Docker CLI.
 *
 * <p>Wraps {@code docker} and {@code docker compose} with ProcessBuilder, in the
 * same shape as {@link RcloneService}: blocking calls intended to be run from a
 * JavaFX {@code Task}, with an optional {@link Consumer} progress callback fed
 * one line at a time, and a cancellable current process.
 *
 * <p>Compose operations target the files deployed to {@code ~/.photostat} by
 * {@link ConfigService#extractBundledComposeFiles}, never the copies in the
 * source tree. The live {@code docker-compose.yml} may have been edited by the
 * user, so GPU support is applied as an overlay ({@code -f docker-compose.gpu.yml})
 * rather than by rewriting that file.
 */
public class DockerService {

    /** Compose project name, passed explicitly so {@code ps} is not sensitive to the file's directory. */
    static final String PROJECT_NAME = "photostat";

    /** Always-required service. */
    public static final String SERVICE_OPENSEARCH = "opensearch";

    /** Optional AI backend services, in the order they appear in the compose file. */
    public static final List<String> OPTIONAL_SERVICES =
            List.of("faces", "analysis", "aesthetic");

    /** All services PhotoStat manages. */
    public static final List<String> ALL_SERVICES =
            List.of(SERVICE_OPENSEARCH, "faces", "analysis", "aesthetic");

    /** Seconds to wait for a quick probe such as {@code docker --version}. */
    private static final int PROBE_TIMEOUT_SECONDS = 15;

    /** Seconds to wait for the engine to come up after launching Docker Desktop. */
    private static final int ENGINE_START_TIMEOUT_SECONDS = 90;

    private static DockerService instance;

    private final ConfigService configService;
    private final LoggingService logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Process currentProcess;

    /** Cached result of {@link #resolveComposeCommand()}; null until first probe. */
    private volatile List<String> composeCommand;

    private DockerService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
    }

    public static synchronized DockerService getInstance() {
        if (instance == null) {
            instance = new DockerService();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /** Overall state of the Docker engine. */
    public enum EngineState {
        /** The docker CLI is not on PATH. */
        NOT_INSTALLED,
        /** The CLI is present but the daemon is not responding. */
        STOPPED,
        /** The daemon is responding. */
        RUNNING
    }

    /** State of a single compose service. */
    public enum ServiceState {
        RUNNING, EXITED, CREATED, RESTARTING, PAUSED, DEAD,
        /** No container exists for this service. */
        ABSENT,
        UNKNOWN
    }

    /** Status of one compose service, as reported by {@code docker compose ps}. */
    public static class ServiceStatus {
        private final String service;
        private final ServiceState state;
        private final String health;
        private final String statusText;

        public ServiceStatus(String service, ServiceState state, String health, String statusText) {
            this.service = service;
            this.state = state;
            this.health = health;
            this.statusText = statusText;
        }

        public String getService() { return service; }
        public ServiceState getState() { return state; }
        /** Container healthcheck state ("healthy", "starting", ...) or empty when the image defines none. */
        public String getHealth() { return health; }
        /** Human-readable status such as "Up 3 minutes". */
        public String getStatusText() { return statusText; }
        public boolean isRunning() { return state == ServiceState.RUNNING; }
    }

    /** One line of output from a running docker command. */
    public static class DockerProgress {
        private final String statusLine;
        private final boolean complete;
        private final String error;

        public DockerProgress(String statusLine, boolean complete, String error) {
            this.statusLine = statusLine;
            this.complete = complete;
            this.error = error;
        }

        public String getStatusLine() { return statusLine; }
        public boolean isComplete() { return complete; }
        public String getError() { return error; }
    }

    /** Result of a docker command. */
    public static class CommandResult {
        private final boolean success;
        private final int exitCode;
        private final String output;
        private final String error;

        public CommandResult(boolean success, int exitCode, String output, String error) {
            this.success = success;
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public int getExitCode() { return exitCode; }
        public String getOutput() { return output; }
        public String getError() { return error; }
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Whether to apply the GPU overlay compose file. Persisted in the config. */
    public boolean isGpuEnabled() {
        return configService.isDockerGpu();
    }

    public void setGpuEnabled(boolean gpuEnabled) {
        configService.setDockerGpu(gpuEnabled);
        configService.saveConfig();
    }

    /** Path to the docker executable, for installs that are not on PATH. */
    public String getDockerPath() {
        String path = configService.getDockerPath();
        return (path == null || path.isBlank()) ? "docker" : path.trim();
    }

    public void setDockerPath(String dockerPath) {
        configService.setDockerPath((dockerPath == null || dockerPath.isBlank())
                ? "docker" : dockerPath.trim());
        configService.saveConfig();
        // A different binary may be a different compose generation.
        this.composeCommand = null;
    }

    /** Directory holding config.json and the deployed compose files ({@code ~/.photostat}). */
    private Path configDir() {
        Path parent = configService.getConfigPath().getParent();
        return parent != null ? parent : Paths.get(System.getProperty("user.home"), ".photostat");
    }

    /** The live compose file, which the user may have edited. */
    public Path getComposeFile() {
        return configDir().resolve("docker-compose.yml");
    }

    /** The GPU overlay compose file. */
    public Path getGpuOverlayFile() {
        return configDir().resolve("docker-compose.gpu.yml");
    }

    /** True when the compose file needed to run any command is present on disk. */
    public boolean isComposeFileAvailable() {
        return Files.isRegularFile(getComposeFile());
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Version of the docker CLI (e.g. "27.3.1"), or null when docker is not on PATH.
     * Does not require a running daemon.
     */
    public String getCliVersion() {
        CommandResult result = run(List.of(getDockerPath(), "--version"), PROBE_TIMEOUT_SECONDS);
        if (!result.isSuccess()) {
            return null;
        }
        return parseCliVersion(result.getOutput());
    }

    /** True when the docker CLI is available, regardless of daemon state. */
    public boolean isCliInstalled() {
        return getCliVersion() != null;
    }

    /**
     * Version of the docker daemon (e.g. "27.3.1"), or null when it is not
     * responding. This is the check that distinguishes "Docker Desktop installed"
     * from "Docker Desktop actually running".
     */
    public String getServerVersion() {
        CommandResult result = run(
                List.of(getDockerPath(), "version", "--format", "{{.Server.Version}}"),
                PROBE_TIMEOUT_SECONDS);
        if (!result.isSuccess()) {
            return null;
        }
        String version = result.getOutput().trim();
        return version.isEmpty() ? null : version;
    }

    /** True when the docker daemon is responding. */
    public boolean isDaemonRunning() {
        return getServerVersion() != null;
    }

    /** Combined engine state, in a single call, for status display. */
    public EngineState getEngineState() {
        if (!isCliInstalled()) {
            return EngineState.NOT_INSTALLED;
        }
        return isDaemonRunning() ? EngineState.RUNNING : EngineState.STOPPED;
    }

    /**
     * Resolve the compose invocation, preferring the v2 plugin
     * ({@code docker compose}) over the v1 standalone binary
     * ({@code docker-compose}). Returns null when neither is available.
     *
     * <p>The result is cached, since the answer cannot change while the app runs
     * unless {@link #setDockerPath} is called.
     */
    public List<String> resolveComposeCommand() {
        List<String> cached = composeCommand;
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String docker = getDockerPath();
        List<String> resolved = List.of();
        if (run(List.of(docker, "compose", "version"), PROBE_TIMEOUT_SECONDS).isSuccess()) {
            resolved = List.of(docker, "compose");
        } else if (run(List.of("docker-compose", "--version"), PROBE_TIMEOUT_SECONDS).isSuccess()) {
            resolved = List.of("docker-compose");
        }

        composeCommand = resolved;
        return resolved.isEmpty() ? null : resolved;
    }

    // ------------------------------------------------------------------
    // Compose operations
    // ------------------------------------------------------------------

    /**
     * Pull the images for the given services (all managed services when null or
     * empty). This is the multi-gigabyte step; callers should warn first.
     */
    public CommandResult composePull(List<String> services, Consumer<DockerProgress> progressCallback) {
        List<String> args = new ArrayList<>();
        args.add("pull");
        addServices(args, services);
        return runCompose(args, progressCallback);
    }

    /** Create and start containers ({@code up -d}). */
    public CommandResult composeUp(List<String> services, Consumer<DockerProgress> progressCallback) {
        List<String> args = new ArrayList<>();
        args.add("up");
        args.add("-d");
        addServices(args, services);
        return runCompose(args, progressCallback);
    }

    /**
     * Start existing containers without recreating them. Faster than
     * {@link #composeUp} and the normal path once setup has run once.
     */
    public CommandResult composeStart(List<String> services, Consumer<DockerProgress> progressCallback) {
        List<String> args = new ArrayList<>();
        args.add("start");
        addServices(args, services);
        return runCompose(args, progressCallback);
    }

    /** Stop containers without removing them. */
    public CommandResult composeStop(List<String> services, Consumer<DockerProgress> progressCallback) {
        List<String> args = new ArrayList<>();
        args.add("stop");
        addServices(args, services);
        return runCompose(args, progressCallback);
    }

    /**
     * Stop and remove containers. Named volumes are not touched, so downloaded
     * model weights and the OpenSearch index survive.
     */
    public CommandResult composeDown(Consumer<DockerProgress> progressCallback) {
        return runCompose(List.of("down"), progressCallback);
    }

    /**
     * Current state of every managed service, keyed by service name. Services
     * with no container are reported as {@link ServiceState#ABSENT} rather than
     * being omitted, so callers can render a complete list.
     */
    public Map<String, ServiceStatus> status() {
        Map<String, ServiceStatus> statuses = new LinkedHashMap<>();
        for (String service : ALL_SERVICES) {
            statuses.put(service, new ServiceStatus(service, ServiceState.ABSENT, "", ""));
        }

        List<String> command = composeCommandFor(List.of("ps", "--format", "json", "--all"));
        if (command == null) {
            return statuses;
        }
        // Do not merge stderr here: compose warnings would corrupt the JSON.
        CommandResult result = run(command, PROBE_TIMEOUT_SECONDS, false);
        if (!result.isSuccess()) {
            return statuses;
        }

        try {
            statuses.putAll(parseComposePs(result.getOutput(), objectMapper));
        } catch (IOException e) {
            logger.warn("DockerService", "Could not parse compose ps output: " + e.getMessage());
        }
        return statuses;
    }

    // ------------------------------------------------------------------
    // Engine startup
    // ------------------------------------------------------------------

    /**
     * Launch Docker Desktop (or the Linux daemon) and wait for it to respond.
     *
     * <p>Docker Desktop routinely takes 30-90 seconds on a cold start and may
     * show its own licence dialog on first run, so this blocks for as long as
     * {@link #ENGINE_START_TIMEOUT_SECONDS} and must never be called on the
     * JavaFX Application Thread.
     *
     * @return null on success, or a message explaining why the engine could not be started
     */
    public String startEngine(Consumer<DockerProgress> progressCallback) {
        if (isDaemonRunning()) {
            emit(progressCallback, "Docker engine is already running", true, null);
            return null;
        }

        String launchError = launchEngineProcess(progressCallback);
        if (launchError != null) {
            emit(progressCallback, launchError, true, launchError);
            return launchError;
        }

        emit(progressCallback, "Waiting for the Docker engine to start...", false, null);
        long deadline = System.currentTimeMillis() + ENGINE_START_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isDaemonRunning()) {
                emit(progressCallback, "Docker engine is running", true, null);
                return null;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String error = "Cancelled while waiting for the Docker engine";
                emit(progressCallback, error, true, error);
                return error;
            }
        }

        String error = "The Docker engine did not start within "
                + ENGINE_START_TIMEOUT_SECONDS + " seconds. "
                + "Start Docker Desktop manually and try again.";
        emit(progressCallback, error, true, error);
        return error;
    }

    /**
     * Fire off the platform-specific engine launch. Returns null when the launch
     * was issued (which says nothing about whether the engine is up yet), or a
     * message when there is nothing we can launch.
     */
    private String launchEngineProcess(Consumer<DockerProgress> progressCallback) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            Path exe = findWindowsDockerDesktop();
            if (exe == null) {
                return "Could not find Docker Desktop. Install it, or start it manually and try again.";
            }
            emit(progressCallback, "Starting Docker Desktop...", false, null);
            return spawnDetached(List.of(exe.toString()));
        }

        if (os.contains("mac")) {
            emit(progressCallback, "Starting Docker Desktop...", false, null);
            return spawnDetached(List.of("open", "-a", "Docker"));
        }

        // Linux: Docker Engine is a system service and starting it needs root, so
        // try the rootless/desktop user unit and otherwise tell the user what to run.
        emit(progressCallback, "Starting the Docker service...", false, null);
        if (run(List.of("systemctl", "--user", "start", "docker-desktop"), PROBE_TIMEOUT_SECONDS).isSuccess()) {
            return null;
        }
        if (run(List.of("systemctl", "--user", "start", "docker"), PROBE_TIMEOUT_SECONDS).isSuccess()) {
            return null;
        }
        return "Could not start the Docker daemon automatically. Run: sudo systemctl start docker";
    }

    /** Usual Docker Desktop install locations on Windows. */
    private Path findWindowsDockerDesktop() {
        List<String> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            candidates.add(programFiles + "\\Docker\\Docker\\Docker Desktop.exe");
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(localAppData + "\\Docker\\Docker Desktop.exe");
        }
        candidates.add("C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe");

        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /** Start a process we do not wait on (the engine UI outlives this call). */
    private String spawnDetached(List<String> command) {
        try {
            logger.info("DockerService", "Launching: " + String.join(" ", command));
            new ProcessBuilder(command).start();
            return null;
        } catch (IOException e) {
            return "Failed to launch " + command.get(0) + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    /** Destroy the running compose process, if any. Used to cancel a long pull. */
    public void cancel() {
        Process process = currentProcess;
        if (process != null && process.isAlive()) {
            logger.info("DockerService", "Cancelling running docker command");
            process.destroy();
        }
    }

    /** True while a streaming compose command is running. */
    public boolean isRunning() {
        Process process = currentProcess;
        return process != null && process.isAlive();
    }

    // ------------------------------------------------------------------
    // Command plumbing
    // ------------------------------------------------------------------

    /**
     * Build a full compose invocation.
     *
     * <p>Static and parameterised so command assembly can be unit tested without
     * a Docker installation.
     *
     * @param composeBase e.g. {@code [docker, compose]} or {@code [docker-compose]}
     * @param composeFile the live compose file
     * @param gpuOverlay  the GPU overlay file, or null to run CPU-only
     * @param args        the compose subcommand and its arguments
     */
    static List<String> buildComposeCommand(List<String> composeBase, Path composeFile,
                                            Path gpuOverlay, List<String> args) {
        List<String> command = new ArrayList<>(composeBase);
        command.add("-f");
        command.add(composeFile.toString());
        if (gpuOverlay != null) {
            command.add("-f");
            command.add(gpuOverlay.toString());
        }
        command.add("-p");
        command.add(PROJECT_NAME);
        command.addAll(args);
        return command;
    }

    /** Append explicit service names, or nothing to mean "all services". */
    private void addServices(List<String> args, List<String> services) {
        if (services != null && !services.isEmpty()) {
            args.addAll(services);
        }
    }

    /**
     * Assemble a compose invocation from the current environment, or null when
     * compose or the compose file is unavailable.
     */
    private List<String> composeCommandFor(List<String> args) {
        List<String> composeBase = resolveComposeCommand();
        if (composeBase == null || !Files.isRegularFile(getComposeFile())) {
            return null;
        }

        Path gpuOverlay = null;
        if (isGpuEnabled()) {
            Path overlay = getGpuOverlayFile();
            if (Files.isRegularFile(overlay)) {
                gpuOverlay = overlay;
            } else {
                logger.warn("DockerService",
                        "GPU enabled but overlay file is missing, falling back to CPU: " + overlay);
            }
        }

        return buildComposeCommand(composeBase, getComposeFile(), gpuOverlay, args);
    }

    /** Resolve compose, assemble the command, and stream it. */
    private CommandResult runCompose(List<String> args, Consumer<DockerProgress> progressCallback) {
        if (resolveComposeCommand() == null) {
            String error = "Docker Compose is not available. Install Docker Desktop, or check that "
                    + "'docker compose version' works from a terminal.";
            emit(progressCallback, error, true, error);
            return new CommandResult(false, -1, "", error);
        }

        Path composeFile = getComposeFile();
        if (!Files.isRegularFile(composeFile)) {
            String error = "Compose file not found: " + composeFile;
            emit(progressCallback, error, true, error);
            return new CommandResult(false, -1, "", error);
        }

        return runStreaming(composeCommandFor(args), args.get(0), progressCallback);
    }

    /**
     * Run a command to completion, feeding each output line to the callback.
     * Blocking; intended for a background thread.
     */
    private CommandResult runStreaming(List<String> command, String label,
                                       Consumer<DockerProgress> progressCallback) {
        logger.info("DockerService", "Running: " + String.join(" ", command));

        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // Compose writes progress to stderr; merge so the callback sees everything.
            pb.redirectErrorStream(true);
            Process process = pb.start();
            currentProcess = process;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    output.append(line).append("\n");
                    emit(progressCallback, line, false, null);
                }
            }

            int exitCode = process.waitFor();
            currentProcess = null;

            if (exitCode == 0) {
                emit(progressCallback, "Complete", true, null);
                return new CommandResult(true, 0, output.toString(), null);
            }

            String error = describeFailure(label, exitCode, output.toString(), getComposeFile());
            emit(progressCallback, error, true, error);
            return new CommandResult(false, exitCode, output.toString(), error);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            currentProcess = null;
            String error = "Interrupted while running docker";
            emit(progressCallback, error, true, error);
            return new CommandResult(false, -1, output.toString(), error);
        } catch (IOException e) {
            currentProcess = null;
            String error = "Failed to run docker: " + e.getMessage();
            emit(progressCallback, error, true, error);
            return new CommandResult(false, -1, output.toString(), error);
        }
    }

    /**
     * Run a short command with a timeout, capturing its output. Used for probes
     * that must not hang the UI if the daemon is wedged.
     */
    private CommandResult run(List<String> command, int timeoutSeconds) {
        return run(command, timeoutSeconds, true);
    }

    /**
     * As {@link #run(List, int)}, but with control over whether stderr is merged
     * into the captured output. Callers that parse structured output must not
     * merge, or diagnostics will corrupt the parse.
     */
    private CommandResult run(List<String> command, int timeoutSeconds, boolean mergeError) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(mergeError);
            if (!mergeError) {
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(false, -1, output.toString(),
                        command.get(0) + " timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, exitCode, output.toString(),
                    exitCode == 0 ? null : "Exit code " + exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(false, -1, "", "Interrupted");
        } catch (IOException e) {
            // Expected when the binary is not on PATH, so this is not logged as an error.
            return new CommandResult(false, -1, "", e.getMessage());
        }
    }

    private void emit(Consumer<DockerProgress> callback, String line, boolean complete, String error) {
        if (callback != null) {
            callback.accept(new DockerProgress(line, complete, error));
        }
    }

    /** Turn a non-zero exit into something a user can act on. */
    static String describeFailure(String label, int exitCode, String output, Path composeFile) {
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("cannot connect to the docker daemon")
                || lower.contains("is the docker daemon running")
                || lower.contains("error during connect")) {
            return "The Docker engine is not running. Start Docker Desktop and try again.";
        }
        if (lower.contains("port is already allocated") || lower.contains("address already in use")) {
            return "A required port is already in use (PhotoStat needs 9200, 8001, 8002 and 8003). "
                    + "Stop whatever is using it, or change the ports in " + composeFile;
        }
        if (lower.contains("no space left on device")) {
            return "Docker ran out of disk space while pulling images.";
        }
        if (lower.contains("no matching manifest")) {
            // The image exists but was not built for this CPU architecture --
            // the usual case is an Apple Silicon Mac against amd64-only images.
            return "The backend images are not published for this computer's processor type ("
                    + System.getProperty("os.arch", "unknown") + "). "
                    + "See docker/README.md for how to run the Intel images under emulation "
                    + "until a matching build is available.";
        }
        if (lower.contains("manifest unknown") || lower.contains("not found")) {
            return "The backend images could not be found in the registry. They may not have "
                    + "been published for this version of PhotoStat yet.";
        }
        if (lower.contains("unauthorized") || lower.contains("denied")) {
            return "The registry refused the download. The PhotoStat images are public, so this "
                    + "is usually a proxy or firewall blocking ghcr.io.";
        }
        return "docker compose " + label + " failed with exit code " + exitCode;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * Extract the version from {@code docker --version} output, which looks like
     * {@code "Docker version 27.3.1, build ce12230"}. Returns null when the
     * output does not match.
     */
    static String parseCliVersion(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("version ");
            if (marker < 0) {
                continue;
            }
            String rest = trimmed.substring(marker + "version ".length()).trim();
            int comma = rest.indexOf(',');
            String version = (comma >= 0 ? rest.substring(0, comma) : rest).trim();
            if (!version.isEmpty()) {
                return version;
            }
        }
        return null;
    }

    /**
     * Parse {@code docker compose ps --format json}.
     *
     * <p>Compose changed this format mid-v2: newer releases emit JSON Lines (one
     * object per line) while older ones emit a single JSON array. Both are
     * accepted here.
     *
     * @return statuses keyed by compose service name
     */
    static Map<String, ServiceStatus> parseComposePs(String output, ObjectMapper mapper) throws IOException {
        Map<String, ServiceStatus> statuses = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return statuses;
        }

        List<JsonNode> entries = new ArrayList<>();
        String trimmed = output.trim();
        if (trimmed.startsWith("[")) {
            mapper.readTree(trimmed).forEach(entries::add);
        } else {
            // JSON Lines. Any non-object line is a stray diagnostic; skip it.
            for (String line : trimmed.split("\\R")) {
                String candidate = line.trim();
                if (candidate.startsWith("{")) {
                    entries.add(mapper.readTree(candidate));
                }
            }
        }

        for (JsonNode entry : entries) {
            String service = entry.path("Service").asText("");
            if (service.isEmpty()) {
                continue;
            }
            statuses.put(service, new ServiceStatus(
                    service,
                    parseServiceState(entry.path("State").asText("")),
                    entry.path("Health").asText(""),
                    entry.path("Status").asText("")));
        }
        return statuses;
    }

    /** Map a compose container state string onto {@link ServiceState}. */
    static ServiceState parseServiceState(String state) {
        if (state == null || state.isBlank()) {
            return ServiceState.UNKNOWN;
        }
        // Compose sometimes decorates the state, e.g. "running (healthy)".
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        int space = normalized.indexOf(' ');
        if (space > 0) {
            normalized = normalized.substring(0, space);
        }
        for (ServiceState candidate : Arrays.asList(
                ServiceState.RUNNING, ServiceState.EXITED, ServiceState.CREATED,
                ServiceState.RESTARTING, ServiceState.PAUSED, ServiceState.DEAD)) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        return ServiceState.UNKNOWN;
    }
}
