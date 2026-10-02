package com.example.pim;

import com.example.pim.outbox.OutboxRelay;
import com.example.pim.outbox.OutboxRepository;
import com.example.pim.outbox.ProductSetPublishedEvent;
import com.example.pim.versioning.ProductSetSnapshot;
import com.example.pim.versioning.ProductVersionRepository;
import com.example.pim.versioning.ProductVersionService;
import com.example.pim.versioning.VersionDiff;
import com.example.pim.versioning.VersionStatus;
import com.example.pim.versioning.VersioningExceptions.InvalidState;
import com.example.pim.versioning.VersioningExceptions.NotFound;
import com.example.pim.versioning.VersioningExceptions.StaleDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs against a real PostgreSQL (see README: PIM_DB_URL / docker compose). The relay bean is
 * disabled so no Kafka broker is needed; the relay is exercised with a mocked KafkaTemplate.
 */
@SpringBootTest(properties = "pim.outbox.relay-enabled=false")
class ProductVersioningIntegrationTest {

    @Autowired
    ProductVersionService service;
    @Autowired
    ProductVersionRepository repo;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox_event, country_version, version_variation, product_set_version, "
                 + "variation_revision, template_revision, product_set CASCADE").update();
    }

    private ProductSetSnapshot createTshirt() {
        Map<String, Map<String, Object>> variations = new LinkedHashMap<>();
        variations.put("S", Map.of("size", "S", "price", 10, "sku", "TS-S"));
        variations.put("M", Map.of("size", "M", "price", 10, "sku", "TS-M"));
        variations.put("L", Map.of("size", "L", "price", 12, "sku", "TS-L"));
        return service.createProductSet("seller-1",
                Map.of("name", "Basic tee", "material", "cotton", "brand", Map.of("name", "Acme", "origin", "EG")),
                variations, "alice");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long outboxCount() {
        return count("outbox_event");
    }

    @Test
    void draftIsNeverPublished() {
        ProductSetSnapshot v1 = createTshirt();

        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v1.variations()).containsOnlyKeys("L", "M", "S");
        assertThat(outboxCount()).isZero();
        assertThatThrownBy(() -> service.publish(v1.versionId(), List.of("EG"), "alice"))
                .isInstanceOf(InvalidState.class);
        assertThatThrownBy(() -> service.getForCountry(v1.productSetId(), "EG"))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void newVersionSharesUnchangedRevisions() {
        ProductSetSnapshot v1 = createTshirt();
        service.approve(v1.versionId(), v1.lockVersion(), "bob");

        ProductSetSnapshot v2 = service.openDraft(v1.productSetId(), "alice");
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.variations()).isEqualTo(v1.variations());

        v2 = service.putVariation(v2.versionId(), v2.lockVersion(), "M", Map.of("size", "M", "price", 11, "sku", "TS-M"));

        // Only one new variation revision was stored; template and S/L are shared with v1.
        assertThat(count("template_revision")).isEqualTo(1);
        assertThat(count("variation_revision")).isEqualTo(4);
        Map<String, Long> m1 = repo.manifest(v1.versionId());
        Map<String, Long> m2 = repo.manifest(v2.versionId());
        assertThat(m2.get("S")).isEqualTo(m1.get("S"));
        assertThat(m2.get("L")).isEqualTo(m1.get("L"));
        assertThat(m2.get("M")).isNotEqualTo(m1.get("M"));

        VersionDiff diff = service.diff(v1.versionId(), v2.versionId());
        assertThat(diff.templateChanged()).isFalse();
        assertThat(diff.changedVariations()).containsExactly("M");
        assertThat(diff.addedVariations()).isEmpty();
        assertThat(diff.removedVariations()).isEmpty();

        // Reverting to the old value reuses the old revision (content-hash dedup), no new row.
        v2 = service.putVariation(v2.versionId(), v2.lockVersion(), "M", Map.of("sku", "TS-M", "price", 10, "size", "M"));
        assertThat(count("variation_revision")).isEqualTo(4);
        assertThat(repo.manifest(v2.versionId()).get("M")).isEqualTo(m1.get("M"));
    }

    @Test
    void eachCountryServesItsOwnApprovedVersion() {
        ProductSetSnapshot v1 = createTshirt();
        UUID set = v1.productSetId();
        service.approve(v1.versionId(), v1.lockVersion(), "bob");
        ProductSetPublishedEvent e1 = service.publish(v1.versionId(), List.of("eg", "SA", "AE"), "bob");
        assertThat(e1.countries()).containsExactly("AE", "EG", "SA");

        ProductSetSnapshot v2 = service.openDraft(set, "alice");
        v2 = service.updateTemplate(v2.versionId(), v2.lockVersion(),
                Map.of("name", "Basic tee (organic)", "material", "organic cotton", "brand", Map.of("name", "Acme", "origin", "EG")));
        v2 = service.approve(v2.versionId(), v2.lockVersion(), "bob");
        ProductSetPublishedEvent e2 = service.publish(v2.versionId(), List.of("EG"), "bob");

        assertThat(service.getForCountry(set, "EG").versionNo()).isEqualTo(2);
        assertThat(service.getForCountry(set, "SA").versionNo()).isEqualTo(1);
        assertThat(service.getForCountry(set, "SA").template()).containsEntry("material", "cotton");
        assertThat(service.countryPointers(set))
                .containsEntry("EG", v2.versionId())
                .containsEntry("SA", v1.versionId())
                .containsEntry("AE", v1.versionId());

        // Rolling EG back to v1 is a newer publication with a higher seq, even though versionNo is lower.
        ProductSetPublishedEvent e3 = service.publish(v1.versionId(), List.of("EG"), "bob");
        assertThat(e3.publishSeq()).isGreaterThan(e2.publishSeq()).isGreaterThan(e1.publishSeq());
        assertThat(service.getForCountry(set, "EG").versionNo()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(3);
    }

    @Test
    void approvedVersionIsImmutable() {
        ProductSetSnapshot v1 = createTshirt();
        ProductSetSnapshot approved = service.approve(v1.versionId(), v1.lockVersion(), "bob");

        assertThatThrownBy(() -> service.putVariation(v1.versionId(), approved.lockVersion(), "XL", Map.of("size", "XL")))
                .isInstanceOf(InvalidState.class);
        assertThatThrownBy(() -> service.approve(v1.versionId(), approved.lockVersion(), "bob"))
                .isInstanceOf(InvalidState.class);

        // The DB triggers block it too, even if a bug bypassed the service.
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM version_variation WHERE version_id = ?").param(v1.versionId()).update())
                .hasMessageContaining("cannot be modified");
    }

    @Test
    void staleLockVersionIsRejected() {
        ProductSetSnapshot v1 = createTshirt();
        int readLock = v1.lockVersion();

        service.putVariation(v1.versionId(), readLock, "XL", Map.of("size", "XL", "price", 13));

        assertThatThrownBy(() -> service.putVariation(v1.versionId(), readLock, "XXL", Map.of("size", "XXL")))
                .isInstanceOf(StaleDraft.class);
    }

    @Test
    void onlyOneDraftAtATime() {
        ProductSetSnapshot v1 = createTshirt();
        service.approve(v1.versionId(), v1.lockVersion(), "bob");

        ProductSetSnapshot d1 = service.openDraft(v1.productSetId(), "alice");
        ProductSetSnapshot d2 = service.openDraft(v1.productSetId(), "carol");
        assertThat(d2.versionId()).isEqualTo(d1.versionId());
    }

    @Test
    void discardedDraftsAreGarbageCollected() {
        ProductSetSnapshot v1 = createTshirt();
        service.approve(v1.versionId(), v1.lockVersion(), "bob");
        ProductSetSnapshot d = service.openDraft(v1.productSetId(), "alice");
        d = service.putVariation(d.versionId(), d.lockVersion(), "M", Map.of("size", "M", "price", 99));
        assertThat(count("variation_revision")).isEqualTo(4);

        service.discard(d.versionId(), d.lockVersion());
        Integer purged = tx.execute(s -> repo.purgeDiscarded(Duration.ZERO));

        assertThat(purged).isEqualTo(1);
        assertThat(count("variation_revision")).isEqualTo(3);
        assertThat(repo.listVersionIds(v1.productSetId())).containsExactly(v1.versionId());
        // v1 is untouched.
        assertThat(service.getVersion(v1.versionId()).variations().get("M")).containsEntry("price", 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void relaySendsCommittedEventsKeyedByProductSet() throws Exception {
        ProductSetSnapshot v1 = createTshirt();
        service.approve(v1.versionId(), v1.lockVersion(), "bob");
        service.publish(v1.versionId(), List.of("EG"), "bob");

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, "pim.product-set.published", 100);

        Integer firstRun = tx.execute(s -> relay.relay());
        Integer secondRun = tx.execute(s -> relay.relay());
        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isZero();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(1)).send(sent.capture());
        assertThat(sent.getValue().key()).isEqualTo(v1.productSetId().toString());
        JsonNode payload = new ObjectMapper().readTree(sent.getValue().value());
        assertThat(payload.get("versionNo").asInt()).isEqualTo(1);
        assertThat(payload.get("countries").get(0).asText()).isEqualTo("EG");
        assertThat(payload.at("/variations/M/sku").asText()).isEqualTo("TS-M");
    }
}
