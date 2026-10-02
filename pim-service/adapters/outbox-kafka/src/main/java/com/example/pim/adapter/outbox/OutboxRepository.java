package com.example.pim.adapter.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class OutboxRepository {

    record PendingEvent(long id, UUID aggregateId, String eventType, String payload) {
    }

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void append(UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO outbox_event (aggregate_id, event_type, payload) VALUES (?, ?, CAST(? AS jsonb))")
                .params(aggregateId, eventType, payloadJson)
                .update();
    }

    /** SKIP LOCKED lets several PIM instances relay in parallel without sending a row twice. */
    List<PendingEvent> lockPending(int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_id, event_type, payload::text
                          FROM outbox_event
                         WHERE published_at IS NULL
                         ORDER BY id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """)
                .param(limit)
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
                .list();
    }

    void markPublished(long id) {
        jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = ?").param(id).update();
    }
}
