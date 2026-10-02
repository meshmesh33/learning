package com.example.pim.domain.event;

import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionNumber;

import java.time.Instant;
import java.util.Collections;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * An approved version became the live version for some countries. Carries the full content so
 * downstream services never need to call back into PIM.
 */
public record ProductSetVersionPublished(
        UUID eventId,
        PublicationSequence sequence,
        ProductSetId productSetId,
        VersionId versionId,
        VersionNumber versionNumber,
        SortedSet<CountryCode> countries,
        Instant publishedAt,
        Attributes template,
        SortedMap<VariationKey, Attributes> variations) {

    public ProductSetVersionPublished {
        countries = Collections.unmodifiableSortedSet(new TreeSet<>(countries));
        variations = Collections.unmodifiableSortedMap(new TreeMap<>(variations));
    }
}
