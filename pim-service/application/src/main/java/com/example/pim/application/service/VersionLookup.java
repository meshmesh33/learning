package com.example.pim.application.service;

import com.example.pim.application.exception.ResourceNotFoundException;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionId;

import java.util.function.Consumer;

/** Shared load-or-404 and load-check-change-save steps used by several use cases. */
final class VersionLookup {

    private final ProductSetVersionRepository versions;

    VersionLookup(ProductSetVersionRepository versions) {
        this.versions = versions;
    }

    ProductSetVersion load(VersionId id) {
        return versions.findById(id).orElseThrow(() -> ResourceNotFoundException.version(id));
    }

    ProductSetVersion change(VersionId id, int expectedLockVersion, Consumer<ProductSetVersion> change) {
        ProductSetVersion version = load(id);
        version.verifyLockVersion(expectedLockVersion);
        change.accept(version);
        return versions.update(version);
    }
}
