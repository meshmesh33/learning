package com.example.pim.domain.exception;

import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;

public class VariationNotFoundException extends DomainException {

    public VariationNotFoundException(VersionId versionId, VariationKey key) {
        super("Variation %s does not exist in version %s".formatted(key, versionId));
    }
}
