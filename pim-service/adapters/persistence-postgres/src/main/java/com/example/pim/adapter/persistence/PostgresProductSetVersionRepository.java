package com.example.pim.adapter.persistence;

import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.ProductSetVersion.Approval;
import com.example.pim.domain.model.ProductSetVersion.State;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionNumber;
import com.example.pim.domain.model.VersionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores a version as a manifest of pointers to shared, content-addressed revisions:
 * changing one variation writes one revision row and swaps one pointer.
 */
@Repository
class PostgresProductSetVersionRepository implements ProductSetVersionRepository {

    private static final String SELECT_VERSION = """
            SELECT v.id, v.product_set_id, v.version_no, v.status, v.based_on_version_id, v.lock_version,
                   v.created_by, v.approved_by, v.approved_at, t.attributes::text AS template
              FROM product_set_version v
              JOIN template_revision t ON t.id = v.template_revision_id
            """;

    private final JdbcClient jdbc;
    private final RevisionStore revisions;
    private final CanonicalJson json;

    PostgresProductSetVersionRepository(JdbcClient jdbc, RevisionStore revisions, CanonicalJson json) {
        this.jdbc = jdbc;
        this.revisions = revisions;
        this.json = json;
    }

    // ---- reads -------------------------------------------------------------------------------

    @Override
    public Optional<ProductSetVersion> findById(VersionId id) {
        return findOne(SELECT_VERSION + " WHERE v.id = ?", id.value());
    }

    @Override
    public Optional<ProductSetVersion> findDraft(ProductSetId productSetId) {
        return findOne(SELECT_VERSION + " WHERE v.product_set_id = ? AND v.status = 'DRAFT'", productSetId.value());
    }

    @Override
    public Optional<ProductSetVersion> findLatestApproved(ProductSetId productSetId) {
        return findOne(SELECT_VERSION + """
                 WHERE v.product_set_id = ? AND v.status = 'APPROVED'
                 ORDER BY v.version_no DESC
                 LIMIT 1
                """, productSetId.value());
    }

    @Override
    public VersionNumber nextVersionNumber(ProductSetId productSetId) {
        int max = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) FROM product_set_version WHERE product_set_id = ?")
                .param(productSetId.value())
                .query(Integer.class)
                .single();
        return new VersionNumber(max + 1);
    }

    // ---- writes ------------------------------------------------------------------------------

    @Override
    public ProductSetVersion add(ProductSetVersion version) {
        State state = version.state();
        jdbc.sql("""
                        INSERT INTO product_set_version
                            (id, product_set_id, version_no, status, template_revision_id, based_on_version_id,
                             lock_version, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(state.id().value(), state.productSetId().value(), state.number().value(), state.status().name(),
                        revisions.storeTemplate(state.productSetId(), state.template()),
                        state.basedOn().map(VersionId::value).orElse(null),
                        state.lockVersion(), state.createdBy().value())
                .update();
        storeManifest(state);
        return version;
    }

    /**
     * Order matters: claim the row first (optimistic lock + row lock), then change content while
     * the row is still DRAFT in the database, then write the new status.
     */
    @Override
    public ProductSetVersion update(ProductSetVersion version) {
        State state = version.state();
        claimForUpdate(state);
        storeManifest(state);
        writeHeader(state);
        return ProductSetVersion.reconstitute(withLockVersion(state, state.lockVersion() + 1));
    }

    private void claimForUpdate(State state) {
        int claimed = jdbc.sql("""
                        UPDATE product_set_version SET lock_version = lock_version + 1
                         WHERE id = ? AND lock_version = ?
                        """)
                .params(state.id().value(), state.lockVersion())
                .update();
        if (claimed == 0) {
            throw new StaleVersionException(state.id());
        }
    }

    private void writeHeader(State state) {
        jdbc.sql("""
                        UPDATE product_set_version
                           SET status = ?, template_revision_id = ?, approved_by = ?, approved_at = ?
                         WHERE id = ?
                        """)
                .params(state.status().name(),
                        revisions.storeTemplate(state.productSetId(), state.template()),
                        state.approval().map(a -> a.approvedBy().value()).orElse(null),
                        state.approval().map(a -> Timestamp.from(a.approvedAt())).orElse(null),
                        state.id().value())
                .update();
    }

    /** Writes only the pointers that differ from what is stored. */
    private void storeManifest(State state) {
        Map<String, Long> stored = loadManifest(state.id());
        Map<String, Long> desired = new HashMap<>();
        state.variations().forEach((key, attributes) ->
                desired.put(key.value(), revisions.storeVariation(state.productSetId(), key, attributes)));

        stored.keySet().stream()
                .filter(key -> !desired.containsKey(key))
                .forEach(key -> jdbc.sql("DELETE FROM version_variation WHERE version_id = ? AND variation_key = ?")
                        .params(state.id().value(), key)
                        .update());

        desired.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(stored.get(entry.getKey())))
                .forEach(entry -> jdbc.sql("""
                                INSERT INTO version_variation (version_id, variation_key, variation_revision_id)
                                VALUES (?, ?, ?)
                                ON CONFLICT (version_id, variation_key)
                                DO UPDATE SET variation_revision_id = EXCLUDED.variation_revision_id
                                """)
                        .params(state.id().value(), entry.getKey(), entry.getValue())
                        .update());
    }

    private Map<String, Long> loadManifest(VersionId id) {
        Map<String, Long> manifest = new HashMap<>();
        jdbc.sql("SELECT variation_key, variation_revision_id FROM version_variation WHERE version_id = ?")
                .param(id.value())
                .query(rs -> {
                    manifest.put(rs.getString(1), rs.getLong(2));
                });
        return manifest;
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Optional<ProductSetVersion> findOne(String sql, Object param) {
        return jdbc.sql(sql)
                .param(param)
                .query(this::mapHeader)
                .optional()
                .map(header -> ProductSetVersion.reconstitute(withVariations(header, loadVariations(header.id()))));
    }

    private Map<VariationKey, Attributes> loadVariations(VersionId id) {
        Map<VariationKey, Attributes> variations = new HashMap<>();
        jdbc.sql("""
                        SELECT vv.variation_key, vr.attributes::text
                          FROM version_variation vv
                          JOIN variation_revision vr ON vr.id = vv.variation_revision_id
                         WHERE vv.version_id = ?
                        """)
                .param(id.value())
                .query(rs -> {
                    variations.put(new VariationKey(rs.getString(1)), json.parse(rs.getString(2)));
                });
        return variations;
    }

    private State mapHeader(ResultSet rs, int rowNum) throws SQLException {
        String approvedBy = rs.getString("approved_by");
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        Optional<Approval> approval = approvedBy == null
                ? Optional.empty()
                : Optional.of(new Approval(new UserId(approvedBy), approvedAt.toInstant()));
        return new State(
                new VersionId(rs.getObject("id", UUID.class)),
                new ProductSetId(rs.getObject("product_set_id", UUID.class)),
                new VersionNumber(rs.getInt("version_no")),
                VersionStatus.valueOf(rs.getString("status")),
                json.parse(rs.getString("template")),
                Map.of(),
                Optional.ofNullable(rs.getObject("based_on_version_id", UUID.class)).map(VersionId::new),
                new UserId(rs.getString("created_by")),
                approval,
                rs.getInt("lock_version"));
    }

    private static State withVariations(State s, Map<VariationKey, Attributes> variations) {
        return new State(s.id(), s.productSetId(), s.number(), s.status(), s.template(), variations,
                s.basedOn(), s.createdBy(), s.approval(), s.lockVersion());
    }

    private static State withLockVersion(State s, int lockVersion) {
        return new State(s.id(), s.productSetId(), s.number(), s.status(), s.template(), s.variations(),
                s.basedOn(), s.createdBy(), s.approval(), lockVersion);
    }
}
