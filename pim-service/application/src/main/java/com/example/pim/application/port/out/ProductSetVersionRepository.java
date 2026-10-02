package com.example.pim.application.port.out;

import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionNumber;

import java.util.Optional;

public interface ProductSetVersionRepository {

    Optional<ProductSetVersion> findById(VersionId id);

    Optional<ProductSetVersion> findDraft(ProductSetId productSetId);

    Optional<ProductSetVersion> findLatestApproved(ProductSetId productSetId);

    /** Next number including discarded drafts, so numbers are never reused. */
    VersionNumber nextVersionNumber(ProductSetId productSetId);

    /** Stores a version that has never been saved. Returns it as persisted. */
    ProductSetVersion add(ProductSetVersion version);

    /**
     * Stores changes to an existing version.
     *
     * @return the version as persisted, with its new lock version
     * @throws StaleVersionException if it was changed since it was loaded
     */
    ProductSetVersion update(ProductSetVersion version);
}
