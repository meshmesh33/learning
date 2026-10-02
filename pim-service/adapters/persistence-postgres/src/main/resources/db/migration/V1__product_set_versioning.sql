-- Product set versioning: immutable revisions + version manifest + per-country pointer.
--
--   product_set ──< template_revision        (immutable, deduplicated by content hash)
--               ──< variation_revision       (immutable, deduplicated by content hash)
--               ──< product_set_version ──< version_variation >── variation_revision
--                         │   (manifest: one template revision + N variation revisions)
--                         └──< country_version (which APPROVED version each country serves)
--
-- The domain only sees whole versions; sharing revisions between versions is purely a storage
-- optimisation implemented by the persistence adapter.

CREATE TABLE product_set (
    id          uuid        PRIMARY KEY,
    seller_id   text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE template_revision (
    id              bigserial   PRIMARY KEY,
    product_set_id  uuid        NOT NULL REFERENCES product_set (id),
    content_hash    bytea       NOT NULL,
    attributes      jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_set_id, content_hash)
);

CREATE TABLE variation_revision (
    id              bigserial   PRIMARY KEY,
    product_set_id  uuid        NOT NULL REFERENCES product_set (id),
    variation_key   text        NOT NULL,
    content_hash    bytea       NOT NULL,
    attributes      jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_set_id, variation_key, content_hash)
);

CREATE TABLE product_set_version (
    id                    uuid        PRIMARY KEY,
    product_set_id        uuid        NOT NULL REFERENCES product_set (id),
    version_no            int         NOT NULL CHECK (version_no >= 1),
    status                text        NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'DISCARDED')),
    template_revision_id  bigint      NOT NULL REFERENCES template_revision (id),
    based_on_version_id   uuid        REFERENCES product_set_version (id),
    lock_version          int         NOT NULL DEFAULT 0,
    created_by            text        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    approved_by           text,
    approved_at           timestamptz,
    UNIQUE (product_set_id, version_no)
);

-- At most one open draft per product set.
CREATE UNIQUE INDEX product_set_version_one_draft
    ON product_set_version (product_set_id) WHERE status = 'DRAFT';

CREATE INDEX product_set_version_latest_approved
    ON product_set_version (product_set_id, version_no DESC) WHERE status = 'APPROVED';

CREATE TABLE version_variation (
    version_id             uuid   NOT NULL REFERENCES product_set_version (id) ON DELETE CASCADE,
    variation_key          text   NOT NULL,
    variation_revision_id  bigint NOT NULL REFERENCES variation_revision (id),
    PRIMARY KEY (version_id, variation_key)
);

-- Used by retention to find revisions no version points to.
CREATE INDEX version_variation_revision ON version_variation (variation_revision_id);

CREATE TABLE country_version (
    product_set_id  uuid        NOT NULL REFERENCES product_set (id),
    country_code    char(2)     NOT NULL,
    version_id      uuid        NOT NULL REFERENCES product_set_version (id),
    publish_seq     bigint      NOT NULL,
    updated_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (product_set_id, country_code)
);

CREATE INDEX country_version_version ON country_version (version_id);

-- Strictly increasing across all publications; consumers order events by it.
CREATE SEQUENCE publish_seq;

-- Safety net behind the domain rules: content of non-draft versions can never change.
CREATE FUNCTION forbid_non_draft_manifest_change() RETURNS trigger AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status
      FROM product_set_version
     WHERE id = COALESCE(NEW.version_id, OLD.version_id);
    IF v_status IS NOT NULL AND v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'version % is % and cannot be modified',
            COALESCE(NEW.version_id, OLD.version_id), v_status;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER version_variation_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON version_variation
    FOR EACH ROW EXECUTE FUNCTION forbid_non_draft_manifest_change();

CREATE FUNCTION forbid_approved_version_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND (
           NEW.status <> OLD.status
        OR NEW.template_revision_id <> OLD.template_revision_id
        OR NEW.version_no <> OLD.version_no) THEN
        RAISE EXCEPTION 'version % is APPROVED and cannot be modified', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER product_set_version_immutable
    BEFORE UPDATE ON product_set_version
    FOR EACH ROW EXECUTE FUNCTION forbid_approved_version_change();
