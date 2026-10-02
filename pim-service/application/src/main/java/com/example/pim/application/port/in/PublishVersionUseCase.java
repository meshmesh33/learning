package com.example.pim.application.port.in;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VersionId;

import java.util.Set;

public interface PublishVersionUseCase {

    /**
     * Makes an approved version live in the given countries and notifies downstream services.
     * Publishing an older approved version is how a country is rolled back.
     */
    ProductSetVersionPublished publish(PublishVersionCommand command);

    record PublishVersionCommand(VersionId versionId, Set<CountryCode> countries, UserId publishedBy) {
    }
}
