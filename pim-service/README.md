# pim-service: product set versioning

A reference implementation of versioned product sets (template + variations) with per-country published versions.
The design rationale is in [`docs/product-set-versioning.md`](../docs/product-set-versioning.md).

## Layout

| Path | What |
|---|---|
| `src/main/resources/db/migration/V1__product_set_versioning.sql` | Schema: revisions, versions, manifest, country pointers, outbox, immutability triggers |
| `versioning/ProductVersionService.java` | Lifecycle: create, open draft, edit, approve, discard, publish, diff |
| `versioning/ProductVersionRepository.java` | SQL (Spring `JdbcClient`): hash-dedup upserts, manifest copy, snapshot read, retention |
| `versioning/CanonicalJson.java` | Sorted-key JSON + SHA-256 content hash |
| `outbox/OutboxRelay.java` | Polls `outbox_event`, sends to Kafka keyed by product set |
| `api/ProductSetController.java` | REST API |
| `src/test/.../ProductVersioningIntegrationTest.java` | End-to-end behaviour against real Postgres |

## Run

```bash
docker compose up -d                 # postgres:16 + kafka
mvn spring-boot:run
```

Run the tests (they need Postgres only; Kafka is mocked):

```bash
docker compose up -d postgres
mvn test
```

Override the connection with `PIM_DB_URL`, `PIM_DB_USER`, `PIM_DB_PASSWORD` and `KAFKA_BOOTSTRAP_SERVERS`.

## Try it

```bash
# 1. create (v1 DRAFT)
curl -s -XPOST localhost:8080/product-sets -H 'X-User: alice' -H 'Content-Type: application/json' -d '{
  "sellerId": "seller-1",
  "template": {"name": "Basic tee", "material": "cotton"},
  "variations": {"S": {"price": 10}, "M": {"price": 10}, "L": {"price": 12}}}'
# → {"productSetId": "<SET>", "versionId": <V1>, "lockVersion": 0, ...}

# 2. approve and publish to countries (this is the only step that emits a Kafka event)
curl -s -XPOST "localhost:8080/versions/<V1>/approve?lockVersion=0" -H 'X-User: bob'
curl -s -XPOST localhost:8080/versions/<V1>/publish -H 'X-User: bob' -H 'Content-Type: application/json' \
     -d '{"countries": ["EG", "SA"]}'

# 3. new draft, change one variation, approve, publish to EG only
curl -s -XPOST localhost:8080/product-sets/<SET>/draft -H 'X-User: alice'          # → versionId <V2>
curl -s -XPUT "localhost:8080/versions/<V2>/variations/M?lockVersion=0" -H 'Content-Type: application/json' -d '{"price": 11}'
curl -s -XPOST "localhost:8080/versions/<V2>/approve?lockVersion=1" -H 'X-User: bob'
curl -s -XPOST localhost:8080/versions/<V2>/publish -H 'X-User: bob' -H 'Content-Type: application/json' -d '{"countries": ["EG"]}'

# 4. inspect
curl -s localhost:8080/product-sets/<SET>/countries          # {"EG": <V2>, "SA": <V1>}
curl -s localhost:8080/product-sets/<SET>/countries/SA        # v1 snapshot
curl -s localhost:8080/versions/<V1>/diff/<V2>                # {"changedVariations": ["M"], ...}
```
