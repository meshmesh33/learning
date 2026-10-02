package com.example.pim.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves committed outbox rows to Kafka. Because the row is written in the same DB transaction as the
 * country pointer, an event exists if and only if the publication committed. Delivery is at-least-once,
 * so consumers must be idempotent (see {@link ProductSetPublishedEvent}).
 *
 * <p>Key = productSetId, so all events for a product set land on one partition and keep their order.
 */
@Component
@ConditionalOnProperty(name = "pim.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       @Value("${pim.outbox.topic}") String topic,
                       @Value("${pim.outbox.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pim.outbox.poll-interval-ms:1000}")
    @Transactional
    public int relay() {
        int sent = 0;
        for (OutboxRepository.OutboxRow row : outbox.lockUnpublished(batchSize)) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, row.aggregateId().toString(), row.payload());
            record.headers().add("event-type", row.eventType().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            } catch (ExecutionException | TimeoutException e) {
                // Stop here to preserve order; rows already sent in this batch are committed below,
                // this one and the rest are retried on the next tick.
                return sent;
            }
            outbox.markPublished(row.id());
            sent++;
        }
        return sent;
    }
}
