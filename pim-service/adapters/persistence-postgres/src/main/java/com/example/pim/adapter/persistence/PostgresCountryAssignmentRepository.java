package com.example.pim.adapter.persistence;

import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class PostgresCountryAssignmentRepository implements CountryAssignmentRepository {

    private final JdbcClient jdbc;

    PostgresCountryAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assign(ProductSetId productSetId, Set<CountryCode> countries, VersionId versionId,
                       PublicationSequence sequence, UserId assignedBy) {
        countries.forEach(country -> jdbc.sql("""
                        INSERT INTO country_version (product_set_id, country_code, version_id, publish_seq, updated_by)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (product_set_id, country_code) DO UPDATE
                           SET version_id  = EXCLUDED.version_id,
                               publish_seq = EXCLUDED.publish_seq,
                               updated_by  = EXCLUDED.updated_by,
                               updated_at  = now()
                        """)
                .params(productSetId.value(), country.value(), versionId.value(), sequence.value(), assignedBy.value())
                .update());
    }

    @Override
    public Optional<VersionId> findLiveVersion(ProductSetId productSetId, CountryCode country) {
        return jdbc.sql("SELECT version_id FROM country_version WHERE product_set_id = ? AND country_code = ?")
                .params(productSetId.value(), country.value())
                .query(UUID.class)
                .optional()
                .map(VersionId::new);
    }

    @Override
    public SortedMap<CountryCode, VersionId> findAll(ProductSetId productSetId) {
        SortedMap<CountryCode, VersionId> assignments = new TreeMap<>();
        jdbc.sql("SELECT country_code, version_id FROM country_version WHERE product_set_id = ?")
                .param(productSetId.value())
                .query(rs -> {
                    assignments.put(new CountryCode(rs.getString(1)), new VersionId(rs.getObject(2, UUID.class)));
                });
        return assignments;
    }
}
