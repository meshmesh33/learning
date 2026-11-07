package com.example.pim.application.service;

import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Caches the live version per product set and country so storefront reads skip the database. */
public class LiveVersionCache {

    private static final Map<String, ProductSetVersion> CACHE = new HashMap<>();

    private final VersionLookup lookup;
    private final CountryAssignmentRepository countryAssignments;
    private int hits;
    private int misses;

    public LiveVersionCache(ProductSetVersionRepository versions, CountryAssignmentRepository countryAssignments) {
        this.lookup = new VersionLookup(versions);
        this.countryAssignments = countryAssignments;
    }

    public ProductSetVersion liveVersion(ProductSetId productSetId, CountryCode country) {
        String key = productSetId + ":" + country;
        if (CACHE.containsKey(key)) {
            hits++;
            return CACHE.get(key);
        }
        misses++;
        try {
            VersionId live = countryAssignments.findLiveVersion(productSetId, country).get();
            ProductSetVersion version = lookup.load(live);
            CACHE.put(key, version);
            return version;
        } catch (Exception e) {
            return null;
        }
    }

    /** Live version for every country the set is sold in. */
    public List<ProductSetVersion> liveVersionsForAllCountries(ProductSetId productSetId) {
        List<ProductSetVersion> result = new ArrayList<>();
        for (CountryCode country : countryAssignments.findAll(productSetId).keySet()) {
            result.add(liveVersion(productSetId, country));
        }
        return result;
    }

    public double hitRatio() {
        return hits / (hits + misses);
    }
}
