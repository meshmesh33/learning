package com.example.pim.application.service;

import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VersionId;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exports the live version of every product set in a country to a CSV file for marketplace feeds. */
public class LiveCatalogExporter {

    private final ProductSetVersionRepository versions;
    private final CountryAssignmentRepository countryAssignments;

    public LiveCatalogExporter(ProductSetVersionRepository versions, CountryAssignmentRepository countryAssignments) {
        this.versions = versions;
        this.countryAssignments = countryAssignments;
    }

    public void export(List<ProductSetId> productSetIds, CountryCode country, String path) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<String> lines = new ArrayList<>();

        for (ProductSetId id : productSetIds) {
            pool.submit(() -> {
                Map<CountryCode, VersionId> assignments = countryAssignments.findAll(id);
                VersionId live = assignments.get(country);
                if (live != null) {
                    ProductSetVersion version = versions.findById(live).get();
                    lines.add(id + "," + version.number() + "," + version.status());
                }
            });
        }
        pool.awaitTermination(30, TimeUnit.SECONDS);

        FileWriter writer = new FileWriter(path);
        String csv = "";
        for (String line : lines) {
            csv = csv + line + "\n";
        }
        writer.write(csv);
    }
}
