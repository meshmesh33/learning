package com.example.pim.application.exception;

import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.VersionId;

public class ResourceNotFoundException extends RuntimeException {

    private ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException productSet(ProductSetId id) {
        return new ResourceNotFoundException("Product set %s not found".formatted(id));
    }

    public static ResourceNotFoundException version(VersionId id) {
        return new ResourceNotFoundException("Version %s not found".formatted(id));
    }

    public static ResourceNotFoundException approvedVersion(ProductSetId id) {
        return new ResourceNotFoundException("Product set %s has no approved version to branch from".formatted(id));
    }

    public static ResourceNotFoundException liveVersion(ProductSetId id, CountryCode country) {
        return new ResourceNotFoundException("Product set %s is not published in %s".formatted(id, country));
    }
}
