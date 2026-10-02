package com.example.pim.versioning;

import com.example.pim.versioning.CanonicalJson.Canonical;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

@Repository
public class ProductVersionRepository {

    public record VersionRow(
            long id,
            UUID productSetId,
            int versionNo,
            VersionStatus status,
            long templateRevisionId,
            int lockVersion) {
    }

    private static final String VERSION_COLUMNS =
            "id, product_set_id, version_no, status, template_revision_id, lock_version";

    private final JdbcClient jdbc;
    private final CanonicalJson json;

    public ProductVersionRepository(JdbcClient jdbc, CanonicalJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- product set -------------------------------------------------------------------------

    public void insertProductSet(UUID id, String sellerId) {
        jdbc.sql("INSERT INTO product_set (id, seller_id) VALUES (?, ?)")
                .params(id, sellerId)
                .update();
    }

    /** Serialises draft creation per product set. */
    public boolean lockProductSet(UUID id) {
        return jdbc.sql("SELECT id FROM product_set WHERE id = ? FOR UPDATE")
                .param(id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    // ---- revisions (insert-if-absent, keyed by content hash) ---------------------------------

    public long upsertTemplateRevision(UUID productSetId, Canonical content) {
        return jdbc.sql("""
                        WITH ins AS (
                            INSERT INTO template_revision (product_set_id, content_hash, attributes)
                            VALUES (:set, :hash, CAST(:attrs AS jsonb))
                            ON CONFLICT (product_set_id, content_hash) DO NOTHING
                            RETURNING id)
                        SELECT id FROM ins
                        UNION ALL
                        SELECT id FROM template_revision WHERE product_set_id = :set AND content_hash = :hash
                        LIMIT 1
                        """)
                .param("set", productSetId)
                .param("hash", content.hash())
                .param("attrs", content.json())
                .query(Long.class)
                .single();
    }

    public long upsertVariationRevision(UUID productSetId, String variationKey, Canonical content) {
        return jdbc.sql("""
                        WITH ins AS (
                            INSERT INTO variation_revision (product_set_id, variation_key, content_hash, attributes)
                            VALUES (:set, :key, :hash, CAST(:attrs AS jsonb))
                            ON CONFLICT (product_set_id, variation_key, content_hash) DO NOTHING
                            RETURNING id)
                        SELECT id FROM ins
                        UNION ALL
                        SELECT id FROM variation_revision
                         WHERE product_set_id = :set AND variation_key = :key AND content_hash = :hash
                        LIMIT 1
                        """)
                .param("set", productSetId)
                .param("key", variationKey)
                .param("hash", content.hash())
                .param("attrs", content.json())
                .query(Long.class)
                .single();
    }

    // ---- versions ----------------------------------------------------------------------------

    public long insertDraft(UUID productSetId, long templateRevisionId, Long basedOnVersionId, String user) {
        return jdbc.sql("""
                        INSERT INTO product_set_version
                            (product_set_id, version_no, status, template_revision_id, based_on_version_id, created_by)
                        VALUES (:set,
                                (SELECT COALESCE(MAX(version_no), 0) + 1 FROM product_set_version WHERE product_set_id = :set),
                                'DRAFT', :template, :basedOn, :user)
                        RETURNING id
                        """)
                .param("set", productSetId)
                .param("template", templateRevisionId)
                .param("basedOn", basedOnVersionId)
                .param("user", user)
                .query(Long.class)
                .single();
    }

    public Optional<VersionRow> findVersion(long versionId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM product_set_version WHERE id = ?")
                .param(versionId)
                .query(this::mapVersion)
                .optional();
    }

    public Optional<VersionRow> findDraft(UUID productSetId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM product_set_version WHERE product_set_id = ? AND status = 'DRAFT'")
                .param(productSetId)
                .query(this::mapVersion)
                .optional();
    }

    public Optional<VersionRow> findLatestApproved(UUID productSetId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                         FROM product_set_version
                        WHERE product_set_id = ? AND status = 'APPROVED'
                        ORDER BY version_no DESC
                        LIMIT 1
                        """)
                .param(productSetId)
                .query(this::mapVersion)
                .optional();
    }

    /** Copies pointers only: O(number of variations), no attribute data is duplicated. */
    public void copyManifest(long fromVersionId, long toVersionId) {
        jdbc.sql("""
                        INSERT INTO version_variation (version_id, variation_key, variation_revision_id)
                        SELECT ?, variation_key, variation_revision_id FROM version_variation WHERE version_id = ?
                        """)
                .params(toVersionId, fromVersionId)
                .update();
    }

    /**
     * Optimistic lock + row lock in one statement. Returns false if the version is missing,
     * not a draft, or the caller's lockVersion is stale.
     */
    public boolean bumpDraftLock(long versionId, int expectedLockVersion) {
        return jdbc.sql("""
                        UPDATE product_set_version
                           SET lock_version = lock_version + 1
                         WHERE id = ? AND status = 'DRAFT' AND lock_version = ?
                        """)
                .params(versionId, expectedLockVersion)
                .update() == 1;
    }

    public void setTemplate(long versionId, long templateRevisionId) {
        jdbc.sql("UPDATE product_set_version SET template_revision_id = ? WHERE id = ?")
                .params(templateRevisionId, versionId)
                .update();
    }

    public void putVariation(long versionId, String variationKey, long variationRevisionId) {
        jdbc.sql("""
                        INSERT INTO version_variation (version_id, variation_key, variation_revision_id)
                        VALUES (?, ?, ?)
                        ON CONFLICT (version_id, variation_key)
                        DO UPDATE SET variation_revision_id = EXCLUDED.variation_revision_id
                        """)
                .params(versionId, variationKey, variationRevisionId)
                .update();
    }

    public boolean removeVariation(long versionId, String variationKey) {
        return jdbc.sql("DELETE FROM version_variation WHERE version_id = ? AND variation_key = ?")
                .params(versionId, variationKey)
                .update() == 1;
    }

    public boolean markApproved(long versionId, int expectedLockVersion, String user) {
        return jdbc.sql("""
                        UPDATE product_set_version
                           SET status = 'APPROVED', approved_by = ?, approved_at = now(), lock_version = lock_version + 1
                         WHERE id = ? AND status = 'DRAFT' AND lock_version = ?
                        """)
                .params(user, versionId, expectedLockVersion)
                .update() == 1;
    }

    public boolean markDiscarded(long versionId, int expectedLockVersion) {
        return jdbc.sql("""
                        UPDATE product_set_version
                           SET status = 'DISCARDED', lock_version = lock_version + 1
                         WHERE id = ? AND status = 'DRAFT' AND lock_version = ?
                        """)
                .params(versionId, expectedLockVersion)
                .update() == 1;
    }

    /** variation key -> variation revision id */
    public Map<String, Long> manifest(long versionId) {
        Map<String, Long> result = new HashMap<>();
        jdbc.sql("SELECT variation_key, variation_revision_id FROM version_variation WHERE version_id = ?")
                .param(versionId)
                .query(rs -> {
                    result.put(rs.getString(1), rs.getLong(2));
                });
        return result;
    }

    // ---- snapshot ----------------------------------------------------------------------------

    public Optional<ProductSetSnapshot> loadSnapshot(long versionId) {
        Optional<VersionRow> version = findVersion(versionId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        VersionRow v = version.get();

        String templateJson = jdbc.sql("SELECT attributes::text FROM template_revision WHERE id = ?")
                .param(v.templateRevisionId())
                .query(String.class)
                .single();

        SortedMap<String, Map<String, Object>> variations = new TreeMap<>();
        jdbc.sql("""
                        SELECT vv.variation_key, vr.attributes::text
                          FROM version_variation vv
                          JOIN variation_revision vr ON vr.id = vv.variation_revision_id
                         WHERE vv.version_id = ?
                        """)
                .param(versionId)
                .query(rs -> {
                    variations.put(rs.getString(1), json.parse(rs.getString(2)));
                });

        return Optional.of(new ProductSetSnapshot(
                v.productSetId(), v.id(), v.versionNo(), v.status(), v.lockVersion(),
                json.parse(templateJson), variations));
    }

    // ---- country pointers --------------------------------------------------------------------

    public long nextPublishSeq() {
        return jdbc.sql("SELECT nextval('publish_seq')").query(Long.class).single();
    }

    public void upsertCountryPointer(UUID productSetId, String countryCode, long versionId, long publishSeq, String user) {
        jdbc.sql("""
                        INSERT INTO country_version (product_set_id, country_code, version_id, publish_seq, updated_by)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (product_set_id, country_code) DO UPDATE
                           SET version_id = EXCLUDED.version_id,
                               publish_seq = EXCLUDED.publish_seq,
                               updated_by = EXCLUDED.updated_by,
                               updated_at = now()
                        """)
                .params(productSetId, countryCode, versionId, publishSeq, user)
                .update();
    }

    public Optional<Long> versionForCountry(UUID productSetId, String countryCode) {
        return jdbc.sql("SELECT version_id FROM country_version WHERE product_set_id = ? AND country_code = ?")
                .params(productSetId, countryCode)
                .query(Long.class)
                .optional();
    }

    /** country code -> version id */
    public Map<String, Long> countryPointers(UUID productSetId) {
        Map<String, Long> result = new TreeMap<>();
        jdbc.sql("SELECT country_code, version_id FROM country_version WHERE product_set_id = ?")
                .param(productSetId)
                .query(rs -> {
                    result.put(rs.getString(1), rs.getLong(2));
                });
        return result;
    }

    // ---- retention ---------------------------------------------------------------------------

    /**
     * Deletes discarded drafts older than the cut-off, then any revision no version points to.
     * Approved versions are kept as the audit trail. Revisions get the same grace period so a
     * draft edit that just reused an old revision cannot lose it mid-transaction.
     */
    public int purgeDiscarded(Duration olderThan) {
        String cutoff = olderThan.toSeconds() + " seconds";
        int versions = jdbc.sql("""
                        DELETE FROM product_set_version v
                         WHERE v.status = 'DISCARDED'
                           AND v.created_at < now() - CAST(? AS interval)
                           AND NOT EXISTS (SELECT 1 FROM product_set_version c WHERE c.based_on_version_id = v.id)
                        """)
                .param(cutoff)
                .update();
        jdbc.sql("""
                        DELETE FROM variation_revision r
                         WHERE r.created_at < now() - CAST(? AS interval)
                           AND NOT EXISTS (SELECT 1 FROM version_variation vv WHERE vv.variation_revision_id = r.id)
                        """)
                .param(cutoff)
                .update();
        jdbc.sql("""
                        DELETE FROM template_revision r
                         WHERE r.created_at < now() - CAST(? AS interval)
                           AND NOT EXISTS (SELECT 1 FROM product_set_version v WHERE v.template_revision_id = r.id)
                        """)
                .param(cutoff)
                .update();
        return versions;
    }

    public List<Long> listVersionIds(UUID productSetId) {
        return jdbc.sql("SELECT id FROM product_set_version WHERE product_set_id = ? ORDER BY version_no")
                .param(productSetId)
                .query(Long.class)
                .list();
    }

    private VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(
                rs.getLong("id"),
                rs.getObject("product_set_id", UUID.class),
                rs.getInt("version_no"),
                VersionStatus.valueOf(rs.getString("status")),
                rs.getLong("template_revision_id"),
                rs.getInt("lock_version"));
    }
}
