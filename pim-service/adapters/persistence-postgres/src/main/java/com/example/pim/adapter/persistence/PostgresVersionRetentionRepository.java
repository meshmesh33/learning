package com.example.pim.adapter.persistence;

import com.example.pim.application.port.out.VersionRetentionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Removes discarded drafts, then revisions nothing points to any more. Revisions get the same
 * cut-off, so a draft edit that has just reused an old revision cannot lose it mid-transaction.
 * Approved versions are kept as the audit trail.
 */
@Repository
class PostgresVersionRetentionRepository implements VersionRetentionRepository {

    private final JdbcClient jdbc;

    PostgresVersionRetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeDiscardedCreatedBefore(Instant cutoff) {
        Timestamp before = Timestamp.from(cutoff);
        int purgedVersions = jdbc.sql("""
                        DELETE FROM product_set_version v
                         WHERE v.status = 'DISCARDED'
                           AND v.created_at < ?
                           AND NOT EXISTS (SELECT 1 FROM product_set_version c WHERE c.based_on_version_id = v.id)
                        """)
                .param(before)
                .update();
        jdbc.sql("""
                        DELETE FROM variation_revision r
                         WHERE r.created_at < ?
                           AND NOT EXISTS (SELECT 1 FROM version_variation vv WHERE vv.variation_revision_id = r.id)
                        """)
                .param(before)
                .update();
        jdbc.sql("""
                        DELETE FROM template_revision r
                         WHERE r.created_at < ?
                           AND NOT EXISTS (SELECT 1 FROM product_set_version v WHERE v.template_revision_id = r.id)
                        """)
                .param(before)
                .update();
        return purgedVersions;
    }
}
