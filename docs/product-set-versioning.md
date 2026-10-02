# Product set versioning in PIM

Stack: Java 21 / Spring Boot, PostgreSQL, Kafka, Gradle.
Implementation: [`pim-service/`](../pim-service), a hexagonal multi-module build (see its README for the module map,
dependency rules and clean-code choices).
Diagrams (component, flow chart, state machines, class, sequence, database schema): [`architecture.md`](architecture.md).

## Problem

- PIM is the source of truth for product sets: a **template** (e.g. "Basic tee") and its **variations** (S / M / L).
- Sellers edit product sets. Each change must become a **version** that holds a snapshot of the values at that time.
- Versions go **DRAFT → APPROVED**. The customer-facing service is told **only** about approved versions, never drafts.
- Each **country** serves a specific approved version (EG on v3 while SA is still on v2).
- Storage and read performance must stay good as versions pile up.

## Chosen design: immutable revisions + version manifest + country pointer

This is option 3 from the earlier discussion: structural sharing, the same idea git uses.

```
product_set
  ├── template_revision   (id, content_hash, attributes jsonb)       immutable, one row per DISTINCT content
  ├── variation_revision  (id, variation_key, content_hash, attrs)   immutable, one row per DISTINCT content
  ├── product_set_version (id, version_no, status, template_revision_id, lock_version)
  │      └── version_variation (version_id, variation_key → variation_revision_id)   ← the "manifest"
  └── country_version     (country_code → version_id, publish_seq)   ← what each country sells
outbox_event              written in the same transaction as country_version, relayed to Kafka
```

A **version** stores no attribute data at all. It is a list of pointers: one template revision plus one
variation revision per variation key.

### Where this lives in the hexagon

The domain (`ProductSetVersion`) models a version as **whole content**: a template plus a map of variations.
It knows nothing about revisions, hashes or manifests. Structural sharing is purely a storage optimisation inside
`adapters/persistence-postgres`:

- `RevisionStore` turns attributes into canonical JSON, hashes them, and inserts the row only if it's absent.
- `PostgresProductSetVersionRepository.update()` rewrites only the manifest pointers that changed.

So you could switch to option 1 (a full JSONB snapshot per version) by replacing one adapter, without touching
the domain, use cases or API.

### Why it is storage-efficient

Changing the price of M in a 3-variation t-shirt:

| | Full snapshot per version (option 1) | This design |
|---|---|---|
| New rows | 1 big JSON document (template + S + M + L) | 1 `variation_revision` (M only) + 3 small pointer rows |
| Template and S/L | copied again | shared with the previous version |

Revisions are deduplicated by a SHA-256 hash of **canonical JSON** (keys sorted). If a seller reverts M's price to the old
value, the old revision row is reused and nothing new is stored (`ON CONFLICT DO NOTHING` on `(product_set_id, variation_key, content_hash)`).

### Why it is fast

- **Read a version**: two indexed queries (version + template, then manifest JOIN variation_revision). No replay, unlike delta storage.
- **Read what a country sells**: one primary-key lookup on `country_version`, then the same snapshot read.
- **Open a draft**: copies pointer rows only (`INSERT … SELECT`), so it's O(#variations) regardless of attribute size.
- **Diff two versions**: `ProductSetVersion.diffTo` compares attribute values in memory. If versions get very
  large, an adapter-level query could compare revision ids instead and load no JSON.
- Hot-path indexes: `country_version` PK, `(product_set_id, version_no)` unique, a partial index for the latest approved version,
  and a partial unique index for "one draft per set".

## Lifecycle

```
createProductSet ─► v1 DRAFT ──edit*──► approve ─► v1 APPROVED ──publish([EG,SA,AE])──► Kafka
                                                     │
openDraft (copies v1 pointers) ─► v2 DRAFT ──edit*──► approve ─► v2 APPROVED ──publish([EG])──► Kafka
                                     └──► discard ─► DISCARDED ─► purged by retention
```

| Rule | How it is enforced |
|---|---|
| Only one open draft per product set | `OpenDraftService` locks the product set (`findAndLock`), plus a partial unique index `WHERE status='DRAFT'` |
| Two editors can't overwrite each other | `verifyLockVersion` in the domain, plus `UPDATE … WHERE lock_version = ?` in the adapter → `StaleVersionException` → 409 |
| Approved versions are immutable | `ProductSetVersion.requireDraft()`, plus DB triggers as a safety net |
| Drafts never reach the consumer | only `ProductSetVersion.publishTo()` creates the event, and it requires `APPROVED` |
| Country pointer and event can't diverge | `PublishVersionService` runs in one transaction; the event port is a transactional outbox |

Approval and publication are deliberately separate steps. This allows staged rollouts (EG first, then SA) and
**rollback** (point EG back at v1, which is just another publish).

## Event to the customer-facing service

`ProductSetPublishedMessage` (the wire contract, mapped from the domain event `ProductSetVersionPublished`),
topic `pim.product-set.published`, Kafka key = `productSetId`:

```json
{
  "eventId": "…", "publishSeq": 42,
  "productSetId": "…", "versionId": "…", "versionNo": 2,
  "countries": ["EG"],
  "template":   { "name": "Basic tee", "material": "cotton", … },
  "variations": { "S": {…}, "M": {…}, "L": {…} }
}
```

- It carries the **full snapshot**, so the consumer stays stateless and never calls back into PIM.
- The **key = productSetId**, so all events for one set stay ordered on one partition.
- **`publishSeq`** (a DB sequence), not `versionNo`, is the ordering key. A rollback to v1 has a *lower* versionNo but is a *newer* decision.
- The outbox relay gives at-least-once delivery, so the consumer must be idempotent:

```sql
-- consumer side, per country in the event
INSERT INTO catalog_product (product_set_id, country, publish_seq, version_no, snapshot)
VALUES (:set, :country, :seq, :versionNo, :snapshot)
ON CONFLICT (product_set_id, country) DO UPDATE
   SET publish_seq = EXCLUDED.publish_seq, version_no = EXCLUDED.version_no, snapshot = EXCLUDED.snapshot
 WHERE catalog_product.publish_seq < EXCLUDED.publish_seq;   -- stale/duplicate events are no-ops
```

### Outbox relay

`OutboxRelay` polls `outbox_event WHERE published_at IS NULL ORDER BY id FOR UPDATE SKIP LOCKED`,
sends to Kafka (idempotent producer, `acks=all`) and marks rows as published. `SKIP LOCKED` lets several PIM instances run the
relay safely. When throughput grows, you can replace the poller with Debezium CDC on `outbox_event` without changing the service.

## Retention

`PurgeDiscardedDraftsUseCase` (scheduled by `bootstrap/RetentionJob`, default: keep 30 days) calls
`PostgresVersionRetentionRepository`, which does the following:

1. Deletes `DISCARDED` drafts older than the cut-off.
2. Deletes `variation_revision` and `template_revision` rows that no version references any more, again only if they're older than the cut-off.
   The grace period stops it from deleting a revision that a concurrent draft edit has just reused.

Approved versions are kept as the audit trail. If they also need to expire, delete approved versions that are
not referenced by `country_version` and are older than N, then run the same revision cleanup.

## Extensions to consider

- **Per-country attribute overrides** (local price, translated title): add `country_override_revision` (same hash/dedup
  pattern) plus `version_country_override(version_id, country, variation_key → revision_id)`. Snapshot = base + overrides.
- **Large catalogues**: partition `variation_revision` and `version_variation` by `product_set_id` hash.
- **Attribute-level sharing**: if variations have hundreds of attributes and only one changes at a time, split them into
  attribute groups (pricing, media, logistics), each with its own revision table. The same pattern applies, just more finely grained.
- **When to choose the simpler option 1** (full JSONB snapshot per version): if sets are small (a few variations, a few KB)
  and edits are rare, the extra tables aren't worth it. Migrating later is mechanical: each snapshot becomes revisions plus a manifest.
