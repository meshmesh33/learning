package com.example.pim.domain.model;

import java.util.UUID;

public record VersionId(UUID value) {

    public VersionId {
        Require.notNull(value, "versionId");
    }

    public static VersionId newId() {
        return new VersionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
