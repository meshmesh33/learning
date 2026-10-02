package com.example.pim.adapter.outbox;

import com.example.pim.adapter.outbox.OutboxRepository.PendingEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Moves committed outbox rows to Kafka, keyed by product set so each set's events stay ordered on
 * one partition. Stops at the first failure to preserve that order; the rest is retried next tick.
 */
@Component
@ConditionalOnProperty(name = "pim.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pim.outbox.poll-interval-ms:1000}")
    @Transactional
    public int relayPending() {
        int sent = 0;
        for (PendingEvent event : outbox.lockPending(properties.batchSize())) {
            if (!send(event)) {
                break;
            }
            outbox.markPublished(event.id());
            sent++;
        }
        return sent;
    }

    private boolean send(PendingEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), event.aggregateId().toString(), event.payload());
        record.headers().add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Outbox event {} not sent, will retry", event.id(), e);
            return false;
        }
    }
}
