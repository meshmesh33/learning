package com.example.pim.domain.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** What changed between two versions of the same product set. */
public record VersionDiff(
        boolean templateChanged,
        SortedSet<VariationKey> addedVariations,
        SortedSet<VariationKey> removedVariations,
        SortedSet<VariationKey> changedVariations) {

    public VersionDiff {
        addedVariations = Collections.unmodifiableSortedSet(new TreeSet<>(addedVariations));
        removedVariations = Collections.unmodifiableSortedSet(new TreeSet<>(removedVariations));
        changedVariations = Collections.unmodifiableSortedSet(new TreeSet<>(changedVariations));
    }

    static VersionDiff between(Attributes fromTemplate, Map<VariationKey, Attributes> fromVariations,
                               Attributes toTemplate, Map<VariationKey, Attributes> toVariations) {
        Set<VariationKey> added = new HashSet<>(toVariations.keySet());
        added.removeAll(fromVariations.keySet());

        Set<VariationKey> removed = new HashSet<>(fromVariations.keySet());
        removed.removeAll(toVariations.keySet());

        Set<VariationKey> changed = new HashSet<>(fromVariations.keySet());
        changed.retainAll(toVariations.keySet());
        changed.removeIf(key -> Objects.equals(fromVariations.get(key), toVariations.get(key)));

        return new VersionDiff(!fromTemplate.equals(toTemplate),
                new TreeSet<>(added), new TreeSet<>(removed), new TreeSet<>(changed));
    }

    public boolean isEmpty() {
        return !templateChanged && addedVariations.isEmpty() && removedVariations.isEmpty() && changedVariations.isEmpty();
    }
}
