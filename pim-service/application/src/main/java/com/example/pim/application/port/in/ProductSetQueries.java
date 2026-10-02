package com.example.pim.application.port.in;

import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionDiff;
import com.example.pim.domain.model.VersionId;

import java.util.SortedMap;

public interface ProductSetQueries {

    ProductSetVersion version(VersionId versionId);

    ProductSetVersion liveVersion(ProductSetId productSetId, CountryCode country);

    SortedMap<CountryCode, VersionId> countryAssignments(ProductSetId productSetId);

    VersionDiff diff(VersionId from, VersionId to);
}
