package com.example.pim.application.port.out;

import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VersionId;

import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

/** Which approved version each country currently sells. */
public interface CountryAssignmentRepository {

    void assign(ProductSetId productSetId, Set<CountryCode> countries, VersionId versionId,
                PublicationSequence sequence, UserId assignedBy);

    Optional<VersionId> findLiveVersion(ProductSetId productSetId, CountryCode country);

    SortedMap<CountryCode, VersionId> findAll(ProductSetId productSetId);
}
