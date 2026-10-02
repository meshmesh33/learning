package com.example.pim.application.service;

import com.example.pim.application.exception.ResourceNotFoundException;
import com.example.pim.application.port.in.ProductSetQueries;
import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionDiff;
import com.example.pim.domain.model.VersionId;

import java.util.SortedMap;

public class ProductSetQueryService implements ProductSetQueries {

    private final VersionLookup lookup;
    private final CountryAssignmentRepository countryAssignments;

    public ProductSetQueryService(ProductSetVersionRepository versions, CountryAssignmentRepository countryAssignments) {
        this.lookup = new VersionLookup(versions);
        this.countryAssignments = countryAssignments;
    }

    @Override
    public ProductSetVersion version(VersionId versionId) {
        return lookup.load(versionId);
    }

    @Override
    public ProductSetVersion liveVersion(ProductSetId productSetId, CountryCode country) {
        VersionId live = countryAssignments.findLiveVersion(productSetId, country)
                .orElseThrow(() -> ResourceNotFoundException.liveVersion(productSetId, country));
        return lookup.load(live);
    }

    @Override
    public SortedMap<CountryCode, VersionId> countryAssignments(ProductSetId productSetId) {
        return countryAssignments.findAll(productSetId);
    }

    @Override
    public VersionDiff diff(VersionId from, VersionId to) {
        return lookup.load(from).diffTo(lookup.load(to));
    }
}
