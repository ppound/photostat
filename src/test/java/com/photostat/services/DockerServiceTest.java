package com.photostat.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DockerService command assembly and output parsing.
 *
 * <p>These cover the pure static helpers only, so they run without Docker
 * installed and without a live daemon.
 */
public class DockerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> COMPOSE_V2 = List.of("docker", "compose");
    private static final List<String> COMPOSE_V1 = List.of("docker-compose");
    private static final Path COMPOSE_FILE = Paths.get("/home/u/.photostat/docker-compose.yml");
    private static final Path GPU_OVERLAY = Paths.get("/home/u/.photostat/docker-compose.gpu.yml");

    // --- buildComposeCommand ---

    @Test
    void buildComposeCommandCpuUsesSingleFile() {
        List<String> command = DockerService.buildComposeCommand(
                COMPOSE_V2, COMPOSE_FILE, null, List.of("up", "-d"));

        assertEquals(List.of(
                "docker", "compose",
                "-f", COMPOSE_FILE.toString(),
                "-p", "photostat",
                "up", "-d"), command);
    }

    @Test
    void buildComposeCommandGpuAppendsOverlayAfterBaseFile() {
        List<String> command = DockerService.buildComposeCommand(
                COMPOSE_V2, COMPOSE_FILE, GPU_OVERLAY, List.of("up", "-d"));

        // Order matters: compose merges later -f files over earlier ones.
        int baseIndex = command.indexOf(COMPOSE_FILE.toString());
        int overlayIndex = command.indexOf(GPU_OVERLAY.toString());
        assertTrue(baseIndex >= 0, "base compose file missing");
        assertTrue(overlayIndex > baseIndex, "GPU overlay must follow the base file");
    }

    @Test
    void buildComposeCommandSupportsStandaloneV1Binary() {
        List<String> command = DockerService.buildComposeCommand(
                COMPOSE_V1, COMPOSE_FILE, null, List.of("stop"));

        assertEquals("docker-compose", command.get(0));
        assertEquals(List.of("-f", COMPOSE_FILE.toString(), "-p", "photostat", "stop"),
                command.subList(1, command.size()));
    }

    @Test
    void buildComposeCommandAlwaysPinsProjectName() {
        List<String> command = DockerService.buildComposeCommand(
                COMPOSE_V2, COMPOSE_FILE, null, List.of("ps"));

        int projectFlag = command.indexOf("-p");
        assertTrue(projectFlag >= 0, "project name must be pinned");
        assertEquals(DockerService.PROJECT_NAME, command.get(projectFlag + 1));
    }

    @Test
    void buildComposeCommandPassesServiceNamesThrough() {
        List<String> command = DockerService.buildComposeCommand(
                COMPOSE_V2, COMPOSE_FILE, null, List.of("up", "-d", "opensearch", "aesthetic"));

        assertEquals("opensearch", command.get(command.size() - 2));
        assertEquals("aesthetic", command.get(command.size() - 1));
    }

    // --- parseCliVersion ---

    @Test
    void parseCliVersionExtractsSemanticVersion() {
        assertEquals("27.3.1",
                DockerService.parseCliVersion("Docker version 27.3.1, build ce12230"));
    }

    @Test
    void parseCliVersionHandlesMissingBuildSuffix() {
        assertEquals("24.0.7", DockerService.parseCliVersion("Docker version 24.0.7"));
    }

    @Test
    void parseCliVersionReturnsNullOnUnrecognisedOutput() {
        assertNull(DockerService.parseCliVersion("command not found"));
        assertNull(DockerService.parseCliVersion(""));
        assertNull(DockerService.parseCliVersion(null));
    }

    // --- parseComposePs ---

    @Test
    void parseComposePsReadsJsonLines() throws Exception {
        String output = """
                {"Name":"photostat-opensearch-1","Service":"opensearch","State":"running","Health":"","Status":"Up 3 minutes"}
                {"Name":"photostat-faces-1","Service":"faces","State":"exited","Health":"","Status":"Exited (0) 1 minute ago"}
                """;

        Map<String, DockerService.ServiceStatus> statuses =
                DockerService.parseComposePs(output, MAPPER);

        assertEquals(2, statuses.size());
        assertEquals(DockerService.ServiceState.RUNNING, statuses.get("opensearch").getState());
        assertTrue(statuses.get("opensearch").isRunning());
        assertEquals("Up 3 minutes", statuses.get("opensearch").getStatusText());
        assertEquals(DockerService.ServiceState.EXITED, statuses.get("faces").getState());
        assertFalse(statuses.get("faces").isRunning());
    }

    @Test
    void parseComposePsReadsLegacyJsonArray() throws Exception {
        String output = """
                [
                  {"Name":"photostat-aesthetic-1","Service":"aesthetic","State":"running","Health":"healthy","Status":"Up 10 seconds"}
                ]
                """;

        Map<String, DockerService.ServiceStatus> statuses =
                DockerService.parseComposePs(output, MAPPER);

        assertEquals(1, statuses.size());
        assertEquals(DockerService.ServiceState.RUNNING, statuses.get("aesthetic").getState());
        assertEquals("healthy", statuses.get("aesthetic").getHealth());
    }

    @Test
    void parseComposePsSkipsStrayDiagnosticLines() throws Exception {
        String output = """
                time="2026-08-27T10:00:00Z" level=warning msg="a stray warning"
                {"Name":"photostat-analysis-1","Service":"analysis","State":"running","Health":"","Status":"Up 1 second"}
                """;

        Map<String, DockerService.ServiceStatus> statuses =
                DockerService.parseComposePs(output, MAPPER);

        assertEquals(1, statuses.size());
        assertTrue(statuses.containsKey("analysis"));
    }

    @Test
    void parseComposePsReturnsEmptyForNoContainers() throws Exception {
        assertTrue(DockerService.parseComposePs("", MAPPER).isEmpty());
        assertTrue(DockerService.parseComposePs("   ", MAPPER).isEmpty());
        assertTrue(DockerService.parseComposePs(null, MAPPER).isEmpty());
    }

    // --- parseServiceState ---

    @Test
    void parseServiceStateMapsKnownStates() {
        assertEquals(DockerService.ServiceState.RUNNING,
                DockerService.parseServiceState("running"));
        assertEquals(DockerService.ServiceState.EXITED,
                DockerService.parseServiceState("exited"));
        assertEquals(DockerService.ServiceState.RESTARTING,
                DockerService.parseServiceState("restarting"));
    }

    @Test
    void parseServiceStateStripsHealthDecoration() {
        assertEquals(DockerService.ServiceState.RUNNING,
                DockerService.parseServiceState("running (healthy)"));
    }

    @Test
    void parseServiceStateFallsBackToUnknown() {
        assertEquals(DockerService.ServiceState.UNKNOWN, DockerService.parseServiceState("weird"));
        assertEquals(DockerService.ServiceState.UNKNOWN, DockerService.parseServiceState(""));
        assertEquals(DockerService.ServiceState.UNKNOWN, DockerService.parseServiceState(null));
    }

    // --- service lists ---

    @Test
    void allServicesMatchesComposeFileContents() {
        assertEquals(List.of("opensearch", "faces", "analysis", "aesthetic"),
                DockerService.ALL_SERVICES);
        assertTrue(DockerService.ALL_SERVICES.containsAll(DockerService.OPTIONAL_SERVICES));
        assertFalse(DockerService.OPTIONAL_SERVICES.contains(DockerService.SERVICE_OPENSEARCH));
    }
}
