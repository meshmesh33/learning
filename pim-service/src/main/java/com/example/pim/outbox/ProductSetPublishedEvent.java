package com.example.pim.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/**
 * Sent to the customer-facing service when an APPROVED version is assigned to one or more countries.
 * Carries the full snapshot so the consumer stays stateless (no diff replay, no callbacks to PIM).
 *
 * <p>Consumers keep, per (productSetId, country), the highest {@code publishSeq} applied and drop
 * anything lower. {@code publishSeq} rather than {@code versionNo} is the ordering key, because rolling
 * a country back to an older version is a legitimate, newer publication.
 */
public record ProductSetPublishedEvent(
        UUID eventId,
        long publishSeq,
        UUID productSetId,
        long versionId,
        int versionNo,
        List<String> countries,
        Instant publishedAt,
        Map<String, Object> template,
        SortedMap<String, Map<String, Object>> variations) {

    public static final String TYPE = "ProductSetPublished";
}
