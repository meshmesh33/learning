-- Transactional outbox: rows are inserted in the same transaction as the business change and
-- relayed to Kafka afterwards, so an event exists if and only if the change committed.
CREATE TABLE outbox_event (
    id            bigserial   PRIMARY KEY,
    aggregate_id  uuid        NOT NULL,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);

CREATE INDEX outbox_event_unpublished ON outbox_event (id) WHERE published_at IS NULL;
