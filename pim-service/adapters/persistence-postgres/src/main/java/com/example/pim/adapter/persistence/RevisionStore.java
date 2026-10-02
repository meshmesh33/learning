package com.example.pim.adapter.persistence;

import com.example.pim.adapter.persistence.CanonicalJson.Canonical;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.VariationKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Content-addressed storage for attribute revisions: storing content that already exists returns
 * the existing row's id instead of writing a copy.
 */
@Component
class RevisionStore {

    private final JdbcClient jdbc;
    private final CanonicalJson json;

    RevisionStore(JdbcClient jdbc, CanonicalJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    long storeTemplate(ProductSetId productSetId, Attributes template) {
        Canonical content = json.canonicalize(template);
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO template_revision (product_set_id, content_hash, attributes)
                            VALUES (:set, :hash, CAST(:attrs AS jsonb))
                            ON CONFLICT (product_set_id, content_hash) DO NOTHING
                            RETURNING id)
                        SELECT id FROM inserted
                        UNION ALL
                        SELECT id FROM template_revision WHERE product_set_id = :set AND content_hash = :hash
                        LIMIT 1
                        """)
                .param("set", productSetId.value())
                .param("hash", content.hash())
                .param("attrs", content.json())
                .query(Long.class)
                .single();
    }

    long storeVariation(ProductSetId productSetId, VariationKey key, Attributes attributes) {
        Canonical content = json.canonicalize(attributes);
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO variation_revision (product_set_id, variation_key, content_hash, attributes)
                            VALUES (:set, :key, :hash, CAST(:attrs AS jsonb))
                            ON CONFLICT (product_set_id, variation_key, content_hash) DO NOTHING
                            RETURNING id)
                        SELECT id FROM inserted
                        UNION ALL
                        SELECT id FROM variation_revision
                         WHERE product_set_id = :set AND variation_key = :key AND content_hash = :hash
                        LIMIT 1
                        """)
                .param("set", productSetId.value())
                .param("key", key.value())
                .param("hash", content.hash())
                .param("attrs", content.json())
                .query(Long.class)
                .single();
    }
}
