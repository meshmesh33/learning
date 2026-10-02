# pim-service: product set versioning

Versioned product sets (template + variations) where each country serves its own approved version.
It's built as a **hexagonal (ports & adapters)** Gradle multi-module project.
The storage design and its rationale are in [`docs/product-set-versioning.md`](../docs/product-set-versioning.md);
the diagrams are in [`docs/architecture.md`](../docs/architecture.md).

## Modules

```
pim-service
├── domain                      plain Java: aggregate, value objects, domain event, business rules
├── application                 use cases (ports/in), what they need (ports/out), services
├── adapters
│   ├── persistence-postgres    implements repository ports; content-addressed revisions; Flyway V1
│   ├── outbox-kafka            implements the event port via a transactional outbox; Kafka relay; Flyway V2
│   └── web                     REST controllers, DTOs, error mapping
└── bootstrap                   Spring Boot app: wires adapters to ports, adds transactions, schedules jobs
```

```
          ┌──────────── adapters (driving) ────────────┐
          │  web: ProductSetController, VersionController
          └──────────────────────┬──────────────────────┘
                                 │ calls ports/in (use cases)
          ┌──────────────────────▼──────────────────────┐
          │ application: CreateProductSet, OpenDraft,   │
          │   EditDraft, ReviewVersion, PublishVersion, │
          │   ProductSetQueries, PurgeDiscardedDrafts   │
          │      ┌───────────────────────────────┐      │
          │      │ domain: ProductSetVersion,    │      │
          │      │   Attributes, CountryCode ... │      │
          │      └───────────────────────────────┘      │
          └──────────────────────┬──────────────────────┘
                                 │ needs ports/out
          ┌──────────────────────▼──────────────────────┐
          │ adapters (driven): persistence-postgres,    │
          │   outbox-kafka                              │
          └─────────────────────────────────────────────┘
```

How the dependency rules are enforced:

| Rule | Enforced by |
|---|---|
| `domain` depends on nothing (not even Spring or Jackson) | Gradle: `domain` has no dependencies; ArchUnit `domainIsPlainJava` |
| `application` uses no framework (no `@Transactional`, no Spring) | Gradle: depends only on `domain`; ArchUnit `applicationIsFrameworkFree` |
| Adapters never depend on each other; dependencies point inwards | Gradle: adapters depend only on `application`; ArchUnit `onionArchitecture` |
| Adapters' libraries don't leak (e.g. `web` can't see JDBC) | Gradle `implementation` (not `api`) scopes |

Transactions are applied from the outside: `bootstrap/TransactionalUseCases` wraps each plain service in a
Spring transaction proxy, so one use-case call is one transaction.

## Clean code choices

- **No primitive obsession.** IDs, codes and keys are value objects: `ProductSetId`, `VersionId`, `CountryCode`
  (validated and upper-cased), `VariationKey`, `VersionNumber` and `PublicationSequence`. Invalid input fails at the edge.
- **Behaviour lives in the aggregate.** `ProductSetVersion` owns the lifecycle rules (`approve`, `discard`,
  `branchDraft`, `publishTo`, `diffTo`). Services only orchestrate.
- **One use case per port.** Commands are immutable records nested in their use case interface.
- **Storage optimisation is hidden.** The domain sees whole versions. Revision dedup and manifests exist only in
  `adapters/persistence-postgres`, so the storage strategy can change without touching business code.
- **Separate wire contract.** `ProductSetPublishedMessage` is the Kafka contract, mapped from the domain event,
  so the domain can be refactored without breaking consumers.
- **An intention-revealing exception hierarchy** is mapped to HTTP in a single place (`ApiExceptionHandler`):
  `InvalidValueException`→400, `ResourceNotFoundException`/`VariationNotFoundException`→404,
  `IllegalVersionStateException`/`StaleVersionException`→409.
- **Time is injected.** Services take a `java.time.Clock`, so tests are deterministic.
- **Compiler warnings are errors** (`-Xlint:all -Werror`, set in `buildSrc/pim.java-conventions`).

## Tests (46)

| Module | Kind | Needs |
|---|---|---|
| `domain` | unit tests of the rules | nothing |
| `application` | use cases with in-memory fakes of every outbound port | nothing |
| `adapters/persistence-postgres` | `@JdbcTest`: structural sharing, dedup, optimistic locking, DB triggers, retention | Postgres |
| `adapters/outbox-kafka` | outbox + relay with mocked Kafka, retry after failure | Postgres |
| `adapters/web` | `@WebMvcTest` HTTP mapping with mocked use cases | nothing |
| `bootstrap` | end-to-end over HTTP + ArchUnit architecture rules | Postgres |

Each database test module uses its own Postgres schema (`persistence_it`, `outbox_it`, `e2e_it`), so they don't interfere.

## Run

```bash
docker compose up -d          # postgres:16 + kafka
./gradlew build               # compile + all tests
./gradlew :bootstrap:bootRun
```

Override the connection with `PIM_DB_URL`, `PIM_DB_USER`, `PIM_DB_PASSWORD` and `KAFKA_BOOTSTRAP_SERVERS`.
Flyway migrations live with the adapter that owns the tables: `V1` in persistence and `V2` in outbox.
New migrations need a version number that's unique across modules.

## Try it

```bash
# 1. create (v1 DRAFT)
curl -s -XPOST localhost:8080/product-sets -H 'X-User: alice' -H 'Content-Type: application/json' -d '{
  "sellerId": "seller-1",
  "template": {"name": "Basic tee", "material": "cotton"},
  "variations": {"S": {"price": 10}, "M": {"price": 10}, "L": {"price": 12}}}'
# → {"productSetId": "<SET>", "versionId": "<V1>", "lockVersion": 0, ...}

# 2. approve, then publish to countries (the only step that emits a Kafka event)
curl -s -XPOST "localhost:8080/versions/<V1>/approve?lockVersion=0" -H 'X-User: bob'
curl -s -XPOST localhost:8080/versions/<V1>/publish -H 'X-User: bob' -H 'Content-Type: application/json' \
     -d '{"countries": ["EG", "SA"]}'

# 3. new draft, change one variation, approve, publish to EG only
curl -s -XPOST localhost:8080/product-sets/<SET>/draft -H 'X-User: alice'          # → versionId <V2>
curl -s -XPUT "localhost:8080/versions/<V2>/variations/M?lockVersion=0" -H 'Content-Type: application/json' -d '{"price": 11}'
curl -s -XPOST "localhost:8080/versions/<V2>/approve?lockVersion=1" -H 'X-User: bob'
curl -s -XPOST localhost:8080/versions/<V2>/publish -H 'X-User: bob' -H 'Content-Type: application/json' -d '{"countries": ["EG"]}'

# 4. inspect
curl -s localhost:8080/product-sets/<SET>/countries          # {"EG": "<V2>", "SA": "<V1>"}
curl -s localhost:8080/product-sets/<SET>/countries/SA        # v1 snapshot
curl -s localhost:8080/versions/<V1>/diff/<V2>                # {"changed": ["M"], ...}
```
