package com.example.pim.adapter.outbox;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.model.CountryCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Public wire contract on topic {@code pim.product-set.published}. Kept separate from the domain
 * event so the domain can evolve without breaking consumers.
 *
 * <p>Consumers keep the highest {@code publishSeq} applied per (productSetId, country) and ignore
 * anything lower; delivery is at-least-once.
 */
public record ProductSetPublishedMessage(
        UUID eventId,
        long publishSeq,
        UUID productSetId,
        UUID versionId,
        int versionNo,
        List<String> countries,
        Instant publishedAt,
        Map<String, Object> template,
        SortedMap<String, Map<String, Object>> variations) {

    public static final String TYPE = "ProductSetPublished";

    static ProductSetPublishedMessage from(ProductSetVersionPublished event) {
        SortedMap<String, Map<String, Object>> variations = new TreeMap<>();
        event.variations().forEach((key, attributes) -> variations.put(key.value(), attributes.values()));
        return new ProductSetPublishedMessage(
                event.eventId(),
                event.sequence().value(),
                event.productSetId().value(),
                event.versionId().value(),
                event.versionNumber().value(),
                event.countries().stream().map(CountryCode::value).toList(),
                event.publishedAt(),
                event.template().values(),
                variations);
    }
}
