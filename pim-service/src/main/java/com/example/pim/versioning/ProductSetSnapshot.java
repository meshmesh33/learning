package com.example.pim.versioning;

import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/** The fully materialised content of one version: template attributes plus every variation. */
public record ProductSetSnapshot(
        UUID productSetId,
        long versionId,
        int versionNo,
        VersionStatus status,
        int lockVersion,
        Map<String, Object> template,
        SortedMap<String, Map<String, Object>> variations) {
}
