package com.example.pim.domain.exception;

import com.example.pim.domain.model.VersionId;

/** Optimistic-lock failure: the caller edited a draft based on an outdated read. */
public class StaleVersionException extends DomainException {

    public StaleVersionException(VersionId id, int expectedLockVersion, int actualLockVersion) {
        super("Version %s was modified concurrently (expected lockVersion %d, current %d)"
                .formatted(id, expectedLockVersion, actualLockVersion));
    }

    public StaleVersionException(VersionId id) {
        super("Version %s was modified concurrently".formatted(id));
    }
}
