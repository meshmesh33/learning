package com.example.pim.adapter.outbox;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionNumber;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({OutboxRepository.class, OutboxEventPublisher.class})
class OutboxAdapterTest {

    @Autowired
    OutboxEventPublisher publisher;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    ObjectMapper objectMapper;

    private final ProductSetId productSetId = ProductSetId.newId();

    private ProductSetVersionPublished publishedEvent() {
        return new ProductSetVersionPublished(UUID.randomUUID(), new PublicationSequence(42), productSetId,
                VersionId.newId(), new VersionNumber(2), new TreeSet<>(Set.of(new CountryCode("EG"))),
                Instant.parse("2026-10-02T10:00:00Z"), Attributes.of(Map.of("name", "Basic tee")),
                new TreeMap<>(Map.of(new VariationKey("M"), Attributes.of(Map.of("price", 11)))));
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaThatAccepts() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return kafka;
    }

    @Test
    @SuppressWarnings("unchecked")
    void relaysEachEventOnceKeyedByProductSet() throws Exception {
        publisher.publish(publishedEvent());
        KafkaTemplate<String, String> kafka = kafkaThatAccepts();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new OutboxProperties("pim.test", 10, 1000));

        int firstRun = relay.relayPending();
        int secondRun = relay.relayPending();

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isZero();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(1)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("pim.test");
        assertThat(sent.getValue().key()).isEqualTo(productSetId.toString());

        JsonNode payload = objectMapper.readTree(sent.getValue().value());
        assertThat(payload.get("publishSeq").asLong()).isEqualTo(42);
        assertThat(payload.get("versionNo").asInt()).isEqualTo(2);
        assertThat(payload.get("countries").get(0).asText()).isEqualTo("EG");
        assertThat(payload.at("/variations/M/price").asInt()).isEqualTo(11);
        assertThat(payload.get("publishedAt").asText()).isEqualTo("2026-10-02T10:00:00Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSendIsRetriedOnTheNextRun() {
        publisher.publish(publishedEvent());
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new OutboxProperties("pim.test", 10, 1000));

        assertThat(relay.relayPending()).isZero();
        assertThat(relay.relayPending()).isEqualTo(1);
    }
}
