package com.example.pim.versioning;

import java.util.SortedSet;

/**
 * Structural diff between two versions. Because unchanged content shares the same revision id,
 * this is computed by comparing pointers only; no attribute JSON is loaded.
 */
public record VersionDiff(
        boolean templateChanged,
        SortedSet<String> addedVariations,
        SortedSet<String> removedVariations,
        SortedSet<String> changedVariations) {
}
