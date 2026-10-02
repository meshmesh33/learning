# PIM architecture diagrams

Diagrams for the product set versioning feature, written in [Mermaid](https://mermaid.js.org) so GitHub
renders them. They mirror the code in [`pim-service/`](../pim-service); the rationale is in
[`product-set-versioning.md`](product-set-versioning.md).

| # | Diagram | Answers |
|---|---|---|
| 1 | [Component diagram](#1-component-diagram) | What are the building blocks and who may depend on whom? |
| 2 | [High-level flow chart](#2-high-level-flow-chart) | How does a seller's change reach customers? |
| 3 | [State machines](#3-state-machines) | What states can a version and a country assignment be in? |
| 4 | [Class diagrams](#4-class-diagrams) | What are the domain model, ports and adapters? |
| 5 | [Sequence diagrams](#5-sequence-diagrams) | What happens, step by step, in each use case? |
| 6 | [Database schema](#6-database-schema) | How is a version stored? |

---

## 1. Component diagram

Gradle modules and the systems around them. Solid arrows are calls and dotted arrows are "implements".
Everything points towards `domain`; adapters never depend on each other. `bootstrap` is the outer shell that
knows all modules: it creates the services, wraps each use case in a transaction, and plugs the adapters into the ports.

```mermaid
flowchart LR
    seller(["Seller tools /<br/>API clients"])

    subgraph boot["bootstrap: Spring Boot app (wires adapters to ports, adds transactions, schedules jobs)"]
        direction LR

        web["<b>adapters:web</b><br/>ProductSetController<br/>VersionController<br/>ApiExceptionHandler"]

        subgraph app["application"]
            direction TB
            inports["<b>ports.in</b><br/>use case interfaces"]
            services["<b>service.*</b><br/>one plain class<br/>per use case"]
            outports["<b>ports.out</b><br/>repositories, sequence,<br/>event publisher"]
            inports --> services --> outports
        end

        model["<b>domain</b><br/>ProductSetVersion aggregate<br/>Attributes, value objects<br/>ProductSetVersionPublished"]

        pg["<b>adapters:persistence-postgres</b><br/>repositories, RevisionStore<br/>CanonicalJson, Flyway V1"]
        ob["<b>adapters:outbox-kafka</b><br/>OutboxEventPublisher, OutboxRelay<br/>wire message, Flyway V2"]
    end

    db[("PostgreSQL")]
    kafka[["Kafka<br/>pim.product-set.published"]]
    customer(["Customer-facing<br/>service"])

    seller -- "HTTP / JSON" --> web
    web -- "calls" --> inports
    services -- "uses" --> model
    pg -. "implements" .-> outports
    ob -. "implements" .-> outports
    pg -- "SQL" --> db
    ob -- "outbox rows" --> db
    ob -- "relay,<br/>at-least-once" --> kafka
    kafka --> customer

    classDef core fill:#e8f1ff,stroke:#3b6fd4,color:#111;
    classDef edge fill:#f4f4f4,stroke:#777,color:#111;
    class model,inports,services,outports core;
    class web,pg,ob edge;
```

| Rule | Enforced by |
|---|---|
| `domain` has no dependencies | Gradle module has none; ArchUnit `domainIsPlainJava` |
| `application` has no framework | depends only on `domain`; ArchUnit `applicationIsFrameworkFree` |
| Adapters only depend on `application` | Gradle `implementation` scopes; ArchUnit `onionArchitecture` |

---

## 2. High-level flow chart

From a seller's edit to what a customer sees. The two rules that matter: **drafts are never sent
downstream**, and **each country is moved to an approved version explicitly**.

```mermaid
flowchart TD
    A([Seller wants to change a product set]) --> B{Open draft<br/>exists?}
    B -- "no" --> C["Branch new draft from<br/>latest approved version<br/>(pointer copy only)"]
    B -- "yes" --> D["Reuse the open draft"]
    C --> E
    D --> E["Edit template and variations<br/>(optimistic lock per edit)"]
    E --> F{Review}
    F -- "discard" --> G["DISCARDED<br/>(purged after retention)"]
    F -- "approve" --> H{"At least one<br/>variation?"}
    H -- "no" --> E
    H -- "yes" --> I["APPROVED<br/>content is frozen"]
    I --> J{"Publish to<br/>which countries?"}
    J --> K["Single transaction:<br/>1. move country pointers<br/>2. append outbox event"]
    K --> L["Outbox relay sends to Kafka<br/>key = productSetId"]
    L --> M["Customer-facing service<br/>applies event if publishSeq is newer"]
    M --> N([Customers in those countries see the new version])
    I -. "later: roll back a country<br/>by publishing an older approved version" .-> J

    classDef stop fill:#fdeaea,stroke:#c0392b,color:#111;
    classDef ok fill:#eaf7ea,stroke:#2e8b3d,color:#111;
    class G stop;
    class I,N ok;
```

---

## 3. State machines

### 3.1 Version lifecycle

Only a `DRAFT` can change. `APPROVED` content is immutable (domain rule + database trigger).

```mermaid
stateDiagram-v2
    direction LR
    [*] --> DRAFT: createProductSet (v1)<br/>branchDraft from APPROVED (v2, v3...)

    DRAFT --> DRAFT: replaceTemplate / putVariation /<br/>removeVariation (lockVersion + 1)
    DRAFT --> APPROVED: approve<br/>[at least one variation]
    DRAFT --> DISCARDED: discard

    APPROVED --> APPROVED: publishTo(countries)<br/>emits ProductSetVersionPublished

    DISCARDED --> [*]: purged after retention (default 30 days)
    APPROVED --> [*]: kept as audit trail

    note right of DRAFT
        At most one DRAFT per product set
        (partial unique index + row lock)
    end note
    note right of APPROVED
        Immutable. A new draft is branched from it,
        never edited in place.
    end note
```

| Illegal action | Result |
|---|---|
| Edit, approve or discard a non-`DRAFT` | `IllegalVersionStateException` → HTTP 409 |
| Publish a non-`APPROVED` version | `IllegalVersionStateException` → HTTP 409 |
| Branch a draft from a non-`APPROVED` version | `IllegalVersionStateException` → HTTP 409 |
| Edit with an outdated `lockVersion` | `StaleVersionException` → HTTP 409 |

### 3.2 Country assignment

One row per (product set, country) in `country_version`. Publishing always moves a country to an
approved version, including an older one (rollback), and stamps a new, higher `publish_seq`.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> NotPublished
    NotPublished --> Live: publish(vN, country)
    Live --> Live: publish(vM, country)<br/>forward or rollback, new publishSeq
    note right of Live
        Live = country_version.version_id points at an APPROVED version.
        Consumers order by publishSeq, not by version number.
    end note
```

---

## 4. Class diagrams

### 4.1 Domain model

```mermaid
classDiagram
    direction LR

    class ProductSetVersion {
        <<aggregate root>>
        -VersionId id
        -ProductSetId productSetId
        -VersionNumber number
        -VersionStatus status
        -Attributes template
        -SortedMap variations
        -Optional~VersionId~ basedOn
        -UserId createdBy
        -Optional~Approval~ approval
        -int lockVersion
        +firstDraft(productSetId, template, variations, createdBy)$ ProductSetVersion
        +reconstitute(State)$ ProductSetVersion
        +branchDraft(VersionNumber, UserId) ProductSetVersion
        +verifyLockVersion(int) void
        +replaceTemplate(Attributes) void
        +putVariation(VariationKey, Attributes) void
        +removeVariation(VariationKey) void
        +approve(UserId, Instant) void
        +discard() void
        +publishTo(Set~CountryCode~, PublicationSequence, Instant) ProductSetVersionPublished
        +diffTo(ProductSetVersion) VersionDiff
        +state() State
    }

    class State {
        <<record>>
        used to rebuild from storage
    }
    class Approval {
        <<record>>
        UserId approvedBy
        Instant approvedAt
    }
    class ProductSet {
        <<record>>
        ProductSetId id
        SellerId sellerId
        +register(SellerId)$ ProductSet
    }
    class Attributes {
        <<record, deeply immutable>>
        Map values
        +of(Map)$ Attributes
        +empty()$ Attributes
    }
    class VersionDiff {
        <<record>>
        boolean templateChanged
        SortedSet addedVariations
        SortedSet removedVariations
        SortedSet changedVariations
    }
    class ProductSetVersionPublished {
        <<domain event>>
        UUID eventId
        PublicationSequence sequence
        ProductSetId productSetId
        VersionId versionId
        VersionNumber versionNumber
        SortedSet countries
        Instant publishedAt
        Attributes template
        SortedMap variations
    }
    class VersionStatus {
        <<enumeration>>
        DRAFT
        APPROVED
        DISCARDED
    }

    class ProductSetId { <<value object>> }
    class VersionId { <<value object>> }
    class SellerId { <<value object>> }
    class UserId { <<value object>> }
    class VersionNumber { <<value object>> }
    class VariationKey { <<value object>> }
    class CountryCode { <<value object>> }
    class PublicationSequence { <<value object>> }

    class DomainException { <<abstract>> }
    class InvalidValueException
    class IllegalVersionStateException
    class StaleVersionException
    class VariationNotFoundException

    ProductSetVersion *-- State
    ProductSetVersion *-- Approval
    ProductSetVersion o-- Attributes : template + variations
    ProductSetVersion --> VersionStatus
    ProductSetVersion ..> VersionDiff : creates
    ProductSetVersion ..> ProductSetVersionPublished : creates
    ProductSetVersion --> ProductSetId
    ProductSetVersion --> VersionId
    ProductSetVersion --> VersionNumber
    ProductSetVersion --> VariationKey
    ProductSetVersion --> UserId
    ProductSet --> SellerId
    ProductSet --> ProductSetId
    ProductSetVersionPublished --> PublicationSequence
    ProductSetVersionPublished --> CountryCode

    DomainException <|-- InvalidValueException
    DomainException <|-- IllegalVersionStateException
    DomainException <|-- StaleVersionException
    DomainException <|-- VariationNotFoundException
```

### 4.2 Application layer: ports and use cases

```mermaid
classDiagram
    direction LR

    class CreateProductSetUseCase { <<port in>> +create(CreateProductSetCommand) ProductSetVersion }
    class OpenDraftUseCase { <<port in>> +openDraft(OpenDraftCommand) ProductSetVersion }
    class EditDraftUseCase {
        <<port in>>
        +replaceTemplate(cmd) ProductSetVersion
        +putVariation(cmd) ProductSetVersion
        +removeVariation(cmd) ProductSetVersion
    }
    class ReviewVersionUseCase {
        <<port in>>
        +approve(ApproveVersionCommand) ProductSetVersion
        +discard(DiscardDraftCommand) void
    }
    class PublishVersionUseCase { <<port in>> +publish(PublishVersionCommand) ProductSetVersionPublished }
    class ProductSetQueries {
        <<port in>>
        +version(VersionId) ProductSetVersion
        +liveVersion(ProductSetId, CountryCode) ProductSetVersion
        +countryAssignments(ProductSetId) SortedMap
        +diff(VersionId, VersionId) VersionDiff
    }
    class PurgeDiscardedDraftsUseCase { <<port in>> +purgeOlderThan(Duration) int }

    class CreateProductSetService
    class OpenDraftService
    class EditDraftService
    class ReviewVersionService
    class PublishVersionService
    class ProductSetQueryService
    class PurgeDiscardedDraftsService
    class VersionLookup { <<package-private helper>> +load(VersionId) +change(id, lock, change) }

    class ProductSetRepository {
        <<port out>>
        +add(ProductSet) void
        +findAndLock(ProductSetId) Optional
    }
    class ProductSetVersionRepository {
        <<port out>>
        +findById(VersionId) Optional
        +findDraft(ProductSetId) Optional
        +findLatestApproved(ProductSetId) Optional
        +nextVersionNumber(ProductSetId) VersionNumber
        +add(ProductSetVersion) ProductSetVersion
        +update(ProductSetVersion) ProductSetVersion
    }
    class CountryAssignmentRepository {
        <<port out>>
        +assign(setId, countries, versionId, seq, user) void
        +findLiveVersion(setId, country) Optional
        +findAll(setId) SortedMap
    }
    class PublicationSequenceGenerator { <<port out>> +next() PublicationSequence }
    class ProductSetEventPublisher { <<port out>> +publish(ProductSetVersionPublished) void }
    class VersionRetentionRepository { <<port out>> +purgeDiscardedCreatedBefore(Instant) int }

    CreateProductSetUseCase <|.. CreateProductSetService
    OpenDraftUseCase <|.. OpenDraftService
    EditDraftUseCase <|.. EditDraftService
    ReviewVersionUseCase <|.. ReviewVersionService
    PublishVersionUseCase <|.. PublishVersionService
    ProductSetQueries <|.. ProductSetQueryService
    PurgeDiscardedDraftsUseCase <|.. PurgeDiscardedDraftsService

    EditDraftService --> VersionLookup
    ReviewVersionService --> VersionLookup
    PublishVersionService --> VersionLookup
    ProductSetQueryService --> VersionLookup
    VersionLookup --> ProductSetVersionRepository

    CreateProductSetService --> ProductSetRepository
    CreateProductSetService --> ProductSetVersionRepository
    OpenDraftService --> ProductSetRepository
    OpenDraftService --> ProductSetVersionRepository
    PublishVersionService --> CountryAssignmentRepository
    PublishVersionService --> PublicationSequenceGenerator
    PublishVersionService --> ProductSetEventPublisher
    ProductSetQueryService --> CountryAssignmentRepository
    PurgeDiscardedDraftsService --> VersionRetentionRepository
```

### 4.3 Adapters

```mermaid
classDiagram
    direction LR

    class ProductSetRepository { <<port out>> }
    class ProductSetVersionRepository { <<port out>> }
    class CountryAssignmentRepository { <<port out>> }
    class PublicationSequenceGenerator { <<port out>> }
    class VersionRetentionRepository { <<port out>> }
    class ProductSetEventPublisher { <<port out>> }

    namespace persistence_postgres {
        class PostgresProductSetRepository
        class PostgresProductSetVersionRepository
        class PostgresCountryAssignmentRepository
        class PostgresPublicationSequenceGenerator
        class PostgresVersionRetentionRepository
        class RevisionStore {
            +storeTemplate(ProductSetId, Attributes) long
            +storeVariation(ProductSetId, VariationKey, Attributes) long
        }
        class CanonicalJson {
            +canonicalize(Attributes) Canonical
            +parse(String) Attributes
        }
    }

    namespace outbox_kafka {
        class OutboxEventPublisher
        class OutboxRepository {
            +append(aggregateId, type, json) void
            +lockPending(limit) List
            +markPublished(id) void
        }
        class OutboxRelay {
            +relayPending() int
        }
        class ProductSetPublishedMessage {
            <<wire contract>>
        }
        class OutboxProperties { <<record>> }
    }

    namespace web {
        class ProductSetController
        class VersionController
        class WebDtos
        class ApiExceptionHandler
    }

    ProductSetRepository <|.. PostgresProductSetRepository
    ProductSetVersionRepository <|.. PostgresProductSetVersionRepository
    CountryAssignmentRepository <|.. PostgresCountryAssignmentRepository
    PublicationSequenceGenerator <|.. PostgresPublicationSequenceGenerator
    VersionRetentionRepository <|.. PostgresVersionRetentionRepository
    ProductSetEventPublisher <|.. OutboxEventPublisher

    PostgresProductSetVersionRepository --> RevisionStore
    PostgresProductSetVersionRepository --> CanonicalJson
    RevisionStore --> CanonicalJson

    OutboxEventPublisher --> OutboxRepository
    OutboxEventPublisher ..> ProductSetPublishedMessage : maps event to
    OutboxRelay --> OutboxRepository
    OutboxRelay --> OutboxProperties

    ProductSetController --> WebDtos
    VersionController --> WebDtos
```

---

## 5. Sequence diagrams

In every diagram below, the `use case` participant is the plain service wrapped by `TransactionalUseCases`,
so each call is **one database transaction**.

### 5.1 Open a draft

```mermaid
sequenceDiagram
    autonumber
    actor Seller
    participant C as ProductSetController
    participant UC as OpenDraftService<br/>(transaction)
    participant PS as ProductSetRepository
    participant VR as ProductSetVersionRepository
    participant V as ProductSetVersion<br/>(approved)

    Seller->>C: POST /product-sets/{id}/draft
    C->>UC: openDraft(productSetId, user)
    UC->>PS: findAndLock(productSetId)
    Note over PS: SELECT ... FOR UPDATE<br/>serialises concurrent requests
    alt product set not found
        UC-->>C: ResourceNotFoundException
        C-->>Seller: 404
    else a draft is already open
        UC->>VR: findDraft(productSetId)
        VR-->>UC: existing draft
        UC-->>C: draft
    else no open draft
        UC->>VR: findDraft(productSetId)
        VR-->>UC: empty
        UC->>VR: findLatestApproved(productSetId)
        VR-->>UC: approved version
        UC->>VR: nextVersionNumber(productSetId)
        VR-->>UC: vN+1
        UC->>V: branchDraft(vN+1, user)
        V-->>UC: new DRAFT (same content)
        UC->>VR: add(draft)
        Note over VR: inserts version row + copies<br/>manifest pointers, no attribute data
        VR-->>UC: persisted draft
        UC-->>C: draft
    end
    C-->>Seller: 200 VersionResponse (lockVersion 0)
```

### 5.2 Edit a variation (optimistic lock + structural sharing)

```mermaid
sequenceDiagram
    autonumber
    actor Seller
    participant C as VersionController
    participant UC as EditDraftService<br/>(transaction)
    participant VR as PostgresProductSetVersionRepository
    participant RS as RevisionStore
    participant DB as PostgreSQL
    participant V as ProductSetVersion

    Seller->>C: PUT /versions/{id}/variations/M?lockVersion=2
    C->>UC: putVariation(versionId, 2, key M, attributes)
    UC->>VR: findById(versionId)
    VR-->>UC: version (lockVersion 2)
    UC->>V: verifyLockVersion(2)
    UC->>V: putVariation(M, attributes)
    Note over V: throws IllegalVersionStateException<br/>unless status is DRAFT
    UC->>VR: update(version)
    VR->>DB: UPDATE version SET lock_version = 3<br/>WHERE id = ? AND lock_version = 2
    alt 0 rows updated
        VR-->>UC: StaleVersionException
        UC-->>C: error
        C-->>Seller: 409 Conflict
    else claimed
        VR->>RS: storeVariation(set, M, attributes)
        RS->>DB: INSERT ... ON CONFLICT (set, key, hash) DO NOTHING
        DB-->>RS: revision id (new or existing)
        VR->>DB: upsert only changed version_variation pointers
        VR->>DB: UPDATE status, template, approval
        VR-->>UC: persisted version (lockVersion 3)
        UC-->>C: version
        C-->>Seller: 200 VersionResponse (lockVersion 3)
    end
```

### 5.3 Approve and publish to countries

```mermaid
sequenceDiagram
    autonumber
    actor Reviewer
    participant C as VersionController
    participant R as ReviewVersionService<br/>(transaction 1)
    participant P as PublishVersionService<br/>(transaction 2)
    participant VR as ProductSetVersionRepository
    participant V as ProductSetVersion
    participant SEQ as PublicationSequenceGenerator
    participant CA as CountryAssignmentRepository
    participant EP as ProductSetEventPublisher<br/>(outbox)
    participant DB as PostgreSQL

    Reviewer->>C: POST /versions/{id}/approve?lockVersion=3
    C->>R: approve(versionId, 3, user)
    R->>VR: findById + verifyLockVersion
    R->>V: approve(user, now)
    Note over V: needs DRAFT and at least one variation
    R->>VR: update(version)
    VR->>DB: status = APPROVED
    R-->>Reviewer: 200 (status APPROVED)

    Reviewer->>C: POST /versions/{id}/publish {countries: [EG, SA]}
    C->>P: publish(versionId, {EG, SA}, user)
    P->>VR: findById(versionId)
    P->>SEQ: next()
    SEQ->>DB: nextval('publish_seq')
    SEQ-->>P: PublicationSequence 42
    P->>V: publishTo({EG, SA}, seq 42, now)
    Note over V: throws unless APPROVED and countries not empty
    V-->>P: ProductSetVersionPublished
    P->>CA: assign(set, {EG, SA}, versionId, seq 42, user)
    CA->>DB: upsert country_version (one row per country)
    P->>EP: publish(event)
    EP->>DB: INSERT outbox_event (same transaction)
    Note over P,DB: COMMIT: pointers and event are saved together or not at all
    P-->>Reviewer: 200 PublicationResponse
```

### 5.4 Outbox relay to the customer-facing service

```mermaid
sequenceDiagram
    autonumber
    participant Sched as Scheduler (every 1s)
    participant Relay as OutboxRelay<br/>(transaction)
    participant DB as PostgreSQL<br/>outbox_event
    participant K as Kafka<br/>pim.product-set.published
    participant Cons as Customer-facing service

    loop each tick
        Sched->>Relay: relayPending()
        Relay->>DB: SELECT unpublished ORDER BY id<br/>LIMIT n FOR UPDATE SKIP LOCKED
        DB-->>Relay: pending events
        loop each event, in order
            Relay->>K: send(key = productSetId, payload)
            alt acknowledged
                K-->>Relay: ack (acks=all, idempotent producer)
                Relay->>DB: UPDATE published_at = now()
            else failed or timed out
                Relay-->>Sched: stop this batch, retry next tick
            end
        end
        Note over Relay,DB: COMMIT marks only the events that were sent
    end

    K->>Cons: ProductSetPublished
    Cons->>Cons: per country: apply only if publishSeq is higher<br/>than the stored one (drops duplicates and stale events)
```

---

## 6. Database schema

Defined in `adapters/persistence-postgres/.../V1__product_set_versioning.sql` and
`adapters/outbox-kafka/.../V2__outbox.sql`.

```mermaid
erDiagram
    product_set ||--o{ template_revision : "has contents"
    product_set ||--o{ variation_revision : "has contents"
    product_set ||--o{ product_set_version : "has versions"
    product_set ||--o{ country_version : "is live in"

    template_revision ||--o{ product_set_version : "used by"
    product_set_version ||--o{ version_variation : "manifest"
    variation_revision ||--o{ version_variation : "used by"
    product_set_version |o--o{ product_set_version : "based_on"
    product_set_version ||--o{ country_version : "served by"

    product_set {
        uuid id PK
        text seller_id
        timestamptz created_at
    }
    template_revision {
        bigserial id PK
        uuid product_set_id FK
        bytea content_hash "UNIQUE with product_set_id"
        jsonb attributes
        timestamptz created_at
    }
    variation_revision {
        bigserial id PK
        uuid product_set_id FK
        text variation_key "UNIQUE with set and hash"
        bytea content_hash
        jsonb attributes
        timestamptz created_at
    }
    product_set_version {
        uuid id PK
        uuid product_set_id FK
        int version_no "UNIQUE with product_set_id"
        text status "DRAFT, APPROVED or DISCARDED"
        bigint template_revision_id FK
        uuid based_on_version_id FK
        int lock_version
        text created_by
        timestamptz created_at
        text approved_by
        timestamptz approved_at
    }
    version_variation {
        uuid version_id PK, FK
        text variation_key PK
        bigint variation_revision_id FK
    }
    country_version {
        uuid product_set_id PK, FK
        char2 country_code PK
        uuid version_id FK
        bigint publish_seq
        text updated_by
        timestamptz updated_at
    }
    outbox_event {
        bigserial id PK
        uuid aggregate_id "product set id, Kafka key"
        text event_type
        jsonb payload
        timestamptz created_at
        timestamptz published_at "null until sent"
    }
```

`outbox_event` has no foreign keys on purpose: it is a transport buffer, not part of the domain model.
The sequence `publish_seq` is global and strictly increasing.

### Constraints and indexes that carry business rules

| Object | Rule it enforces |
|---|---|
| `UNIQUE (product_set_id, content_hash)` on `template_revision` | identical template content is stored once |
| `UNIQUE (product_set_id, variation_key, content_hash)` on `variation_revision` | identical variation content is stored once |
| `UNIQUE (product_set_id, version_no)` | version numbers never repeat within a product set |
| partial unique index `product_set_version_one_draft` `WHERE status = 'DRAFT'` | at most one open draft per product set |
| partial index `product_set_version_latest_approved` `(product_set_id, version_no DESC) WHERE status = 'APPROVED'` | fast "latest approved version" lookup |
| `PRIMARY KEY (product_set_id, country_code)` on `country_version` | one live version per country, and a single-row lookup per country |
| `CHECK (status IN (...))`, `CHECK (version_no >= 1)` | valid lifecycle values |
| trigger `version_variation_immutable` | rejects any change to the manifest of a non-draft version |
| trigger `product_set_version_immutable` | rejects changing status, template or number of an `APPROVED` version |
| partial index `outbox_event_unpublished` `WHERE published_at IS NULL` | the relay's polling query stays cheap as history grows |
| index `version_variation_revision` | retention can find revisions that nothing points to |

### Why a version stores no attribute data

```mermaid
flowchart LR
    subgraph v1["product_set_version v1 (APPROVED)"]
        m1S["S → rev 1"]
        m1M["M → rev 2"]
        m1L["L → rev 3"]
        t1["template → trev 1"]
    end
    subgraph v2["product_set_version v2 (DRAFT)"]
        m2S["S → rev 1"]
        m2M["M → rev 4"]
        m2L["L → rev 3"]
        t2["template → trev 1"]
    end
    r1[("variation_revision 1<br/>S, price 10")]
    r2[("variation_revision 2<br/>M, price 10")]
    r3[("variation_revision 3<br/>L, price 12")]
    r4[("variation_revision 4<br/>M, price 11")]
    tr1[("template_revision 1")]

    m1S --> r1
    m2S --> r1
    m1M --> r2
    m2M --> r4
    m1L --> r3
    m2L --> r3
    t1 --> tr1
    t2 --> tr1
```

Changing M's price created one new `variation_revision` row (4). Everything else is shared by pointer.
