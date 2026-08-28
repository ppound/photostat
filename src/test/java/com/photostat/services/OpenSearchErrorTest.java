package com.photostat.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenSearchService.describeError.
 *
 * <p>These messages are all a user sees when indexing fails, so a recognised
 * condition must explain itself rather than leaking the client's wording.
 */
public class OpenSearchErrorTest {

    @Test
    void floodStageWatermarkExplainsTheFullDisk() {
        // The real message from a disk over the 95% flood-stage watermark.
        Exception e = new IOException("OpenSearch exception [type=cluster_block_exception, "
                + "reason=index [photostat] blocked by: "
                + "[TOO_MANY_REQUESTS/12/disk usage exceeded flood-stage watermark, "
                + "index has read-only-allow-delete block];]");

        String message = OpenSearchService.describeError(e);

        assertTrue(message.toLowerCase().contains("disk"), message);
        assertTrue(message.toLowerCase().contains("read-only"), message);
        // Must point at the right disk: users assume it means their photo drive.
        assertTrue(message.contains("Docker disk image"), message);
        assertFalse(message.contains("TOO_MANY_REQUESTS"),
                "should not leak the raw cluster block code");
    }

    @Test
    void readOnlyBlockIsRecognisedFromSettingsWording() {
        String message = OpenSearchService.describeError(
                new IOException("blocked by: [FORBIDDEN/12/index read-only / allow delete (api)]"));

        assertTrue(message.toLowerCase().contains("disk"), message);
    }

    @Test
    void bareForbiddenStillGivesGuidance() {
        // What the user actually saw: four unhelpful words.
        String message = OpenSearchService.describeError(new IOException("Forbidden access"));

        assertNotEquals("Forbidden access", message);
        assertTrue(message.toLowerCase().contains("read-only")
                        || message.toLowerCase().contains("permission"),
                message);
    }

    @Test
    void connectionRefusedPointsAtTheServicesTab() {
        String message = OpenSearchService.describeError(
                new IOException("Connection refused: localhost/127.0.0.1:9200"));

        assertTrue(message.contains("Services tab"), message);
    }

    @Test
    void circuitBreakerSuggestsBatchSize() {
        String message = OpenSearchService.describeError(
                new IOException("[circuit_breaking_exception] [parent] Data too large"));

        assertTrue(message.toLowerCase().contains("batch size"), message);
    }

    @Test
    void nestedCauseIsInspected() {
        // The client wraps the useful detail several layers down.
        Exception root = new IOException("index has read-only-allow-delete block");
        Exception wrapped = new RuntimeException("Bulk request failed", root);

        assertTrue(OpenSearchService.describeError(wrapped).toLowerCase().contains("disk"));
    }

    @Test
    void unrecognisedErrorsKeepTheirOriginalMessage() {
        String message = OpenSearchService.describeError(
                new IOException("something entirely unexpected"));

        assertTrue(message.contains("something entirely unexpected"), message);
    }

    @Test
    void nullAndEmptyAreHandled() {
        assertEquals("Unknown error", OpenSearchService.describeError(null));
        assertNotNull(OpenSearchService.describeError(new IOException()));
    }

    @Test
    void selfReferencingCauseDoesNotLoop() {
        // A cause chain that points at itself must not hang the indexer.
        Exception e = new IOException("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertNotNull(OpenSearchService.describeError(e));
    }
}
