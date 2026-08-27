package com.photostat.services;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the security posture of the bundled Docker Compose files.
 *
 * <p>OpenSearch is shipped with {@code plugins.security.disabled=true} and the
 * AI backends accept unauthenticated requests, so publishing any of their ports
 * on all interfaces would expose a user's photo index to their whole network.
 * These tests fail the build if that regresses.
 */
public class ComposeSecurityTest {

    private String bundledResource(String name) throws Exception {
        try (InputStream is = ConfigService.class.getResourceAsStream(name)) {
            assertNotNull(is, "bundled compose resource missing: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- shipped compose files ---

    @Test
    void bundledComposeBindsEveryPortToLoopback() throws Exception {
        String compose = bundledResource("/docker-compose.yml");

        assertEquals(List.of(), ConfigService.findUnboundPortPublishes(compose),
                "bundled docker-compose.yml must not publish ports on all interfaces");
        // And the mappings we do expect are present, so the assertion above
        // cannot pass merely because the ports were removed.
        for (String port : List.of("9200", "8001", "8002", "8003")) {
            assertTrue(compose.contains("127.0.0.1:" + port + ":" + port),
                    "expected loopback-bound mapping for port " + port);
        }
    }

    @Test
    void bundledComposePinsImagesToAVersionedTag() throws Exception {
        String compose = bundledResource("/docker-compose.yml");

        // Floating :cpu tags would let a registry compromise change what an
        // already-installed version runs.
        assertFalse(compose.contains(":cpu\n") || compose.contains(":cpu\r\n"),
                "images must not use the floating :cpu tag");
        for (String service : List.of("faces", "analysis", "aesthetic")) {
            assertTrue(compose.matches("(?s).*photostat-" + service + ":\\d+\\.\\d+\\.\\d+-cpu.*"),
                    "photostat-" + service + " must pin a versioned cpu tag");
        }
    }

    @Test
    void bundledGpuOverlayPinsImagesToAVersionedTag() throws Exception {
        String compose = bundledResource("/docker-compose.gpu.yml");

        assertFalse(compose.contains(":gpu\n") || compose.contains(":gpu\r\n"),
                "images must not use the floating :gpu tag");
        for (String service : List.of("faces", "analysis", "aesthetic")) {
            assertTrue(compose.matches("(?s).*photostat-" + service + ":\\d+\\.\\d+\\.\\d+-gpu.*"),
                    "photostat-" + service + " must pin a versioned gpu tag");
        }
    }

    // --- findUnboundPortPublishes ---

    @Test
    void detectsBarePortMappingAsUnbound() {
        String compose = """
                services:
                  opensearch:
                    ports:
                      - "9200:9200"
                """;
        assertEquals(List.of("9200:9200"), ConfigService.findUnboundPortPublishes(compose));
    }

    @Test
    void acceptsLoopbackBoundMapping() {
        String compose = """
                services:
                  opensearch:
                    ports:
                      - "127.0.0.1:9200:9200"
                """;
        assertTrue(ConfigService.findUnboundPortPublishes(compose).isEmpty());
    }

    @Test
    void detectsUnquotedAndSingleQuotedMappings() {
        assertEquals(List.of("8001:8001"),
                ConfigService.findUnboundPortPublishes("    - 8001:8001"));
        assertEquals(List.of("8002:8002"),
                ConfigService.findUnboundPortPublishes("    - '8002:8002'"));
    }

    @Test
    void ignoresCommentedOutMappings() {
        String compose = """
                    ports:
                      # - "9200:9200"
                      - "127.0.0.1:9200:9200"
                """;
        assertTrue(ConfigService.findUnboundPortPublishes(compose).isEmpty());
    }

    @Test
    void ignoresNonPortListEntries() {
        String compose = """
                    environment:
                      - discovery.type=single-node
                      - PHOTOSTAT_IQA_METRIC=nima
                    volumes:
                      - opensearch-data:/usr/share/opensearch/data
                """;
        assertTrue(ConfigService.findUnboundPortPublishes(compose).isEmpty());
    }

    @Test
    void reportsEveryExposedMapping() {
        String compose = """
                      - "9200:9200"
                      - "127.0.0.1:8001:8001"
                      - "8002:8002"
                """;
        assertEquals(List.of("9200:9200", "8002:8002"),
                ConfigService.findUnboundPortPublishes(compose));
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertTrue(ConfigService.findUnboundPortPublishes(null).isEmpty());
        assertTrue(ConfigService.findUnboundPortPublishes("").isEmpty());
    }
}
