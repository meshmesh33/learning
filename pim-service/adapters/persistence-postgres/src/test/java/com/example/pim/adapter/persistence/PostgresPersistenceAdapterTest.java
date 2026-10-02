package com.example.pim.adapter.persistence;

import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSet;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.SellerId;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against real PostgreSQL (schema {@code persistence_it}); each test rolls back.
 * Focuses on what only the adapter can guarantee: structural sharing, dedup, locking, triggers.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CanonicalJson.class, RevisionStore.class, PostgresProductSetRepository.class,
        PostgresProductSetVersionRepository.class, PostgresCountryAssignmentRepository.class,
        PostgresPublicationSequenceGenerator.class, PostgresVersionRetentionRepository.class})
class PostgresPersistenceAdapterTest {

    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final VariationKey S = new VariationKey("S");
    private static final VariationKey M = new VariationKey("M");
    private static final VariationKey L = new VariationKey("L");

    @Autowired
    PostgresProductSetRepository productSets;
    @Autowired
    PostgresProductSetVersionRepository versions;
    @Autowired
    PostgresCountryAssignmentRepository countries;
    @Autowired
    PostgresPublicationSequenceGenerator sequences;
    @Autowired
    PostgresVersionRetentionRepository retention;
    @Autowired
    JdbcClient jdbc;

    private ProductSet tshirt;

    @BeforeEach
    void registerProductSet() {
        tshirt = ProductSet.register(new SellerId("seller-1"));
        productSets.add(tshirt);
    }

    private ProductSetVersion storedApprovedV1() {
        ProductSetVersion v1 = versions.add(ProductSetVersion.firstDraft(tshirt.id(),
                Attributes.of(Map.of("name", "Basic tee", "brand", Map.of("name", "Acme", "origin", "EG"))),
                Map.of(S, price(10), M, price(10), L, price(12)),
                ALICE));
        v1.approve(BOB, Instant.now().truncatedTo(ChronoUnit.MICROS));
        return versions.update(v1);
    }

    private static Attributes price(int price) {
        return Attributes.of(Map.of("price", price));
    }

    private long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE product_set_id = ?")
                .param(tshirt.id().value()).query(Long.class).single();
    }

    @Test
    void roundTripsAVersion() {
        ProductSetVersion v1 = storedApprovedV1();

        ProductSetVersion loaded = versions.findById(v1.id()).orElseThrow();

        assertThat(loaded.state()).isEqualTo(v1.state());
        assertThat(loaded.status()).isEqualTo(VersionStatus.APPROVED);
        assertThat(loaded.lockVersion()).isEqualTo(1);
    }

    @Test
    void newVersionStoresOnlyWhatChanged() {
        ProductSetVersion v1 = storedApprovedV1();
        ProductSetVersion v2 = versions.add(v1.branchDraft(versions.nextVersionNumber(tshirt.id()), ALICE));

        v2.putVariation(M, price(11));
        versions.update(v2);

        assertThat(rows("template_revision")).isEqualTo(1);
        assertThat(rows("variation_revision")).isEqualTo(4);
        assertThat(versions.findById(v1.id()).orElseThrow().variations().get(M)).isEqualTo(price(10));
        assertThat(versions.findById(v2.id()).orElseThrow().variations().get(M)).isEqualTo(price(11));
    }

    @Test
    void identicalContentIsStoredOnce() {
        ProductSetVersion v1 = storedApprovedV1();
        ProductSetVersion v2 = versions.add(v1.branchDraft(versions.nextVersionNumber(tshirt.id()), ALICE));

        v2.putVariation(M, price(11));
        v2 = versions.update(v2);
        v2.putVariation(M, price(10));
        versions.update(v2);

        assertThat(rows("variation_revision")).isEqualTo(4);
    }

    @Test
    void concurrentEditOfTheSameDraftIsDetected() {
        ProductSetVersion draft = versions.add(ProductSetVersion.firstDraft(tshirt.id(), Attributes.empty(),
                Map.of(S, price(10)), ALICE));
        ProductSetVersion aliceCopy = versions.findById(draft.id()).orElseThrow();
        ProductSetVersion bobCopy = versions.findById(draft.id()).orElseThrow();

        aliceCopy.putVariation(M, price(10));
        versions.update(aliceCopy);
        bobCopy.putVariation(L, price(12));

        assertThatThrownBy(() -> versions.update(bobCopy)).isInstanceOf(StaleVersionException.class);
    }

    @Test
    void databaseRejectsChangesToApprovedContent() {
        ProductSetVersion v1 = storedApprovedV1();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM version_variation WHERE version_id = ?")
                .param(v1.id().value()).update())
                .hasMessageContaining("cannot be modified");
    }

    @Test
    void findsDraftAndLatestApproved() {
        ProductSetVersion v1 = storedApprovedV1();
        ProductSetVersion v2 = versions.add(v1.branchDraft(versions.nextVersionNumber(tshirt.id()), ALICE));

        assertThat(versions.findDraft(tshirt.id()).map(ProductSetVersion::id)).contains(v2.id());
        assertThat(versions.findLatestApproved(tshirt.id()).map(ProductSetVersion::id)).contains(v1.id());
        assertThat(versions.nextVersionNumber(tshirt.id()).value()).isEqualTo(3);
        assertThat(productSets.findAndLock(tshirt.id())).contains(tshirt);
    }

    @Test
    void countryAssignmentsPointAtVersions() {
        ProductSetVersion v1 = storedApprovedV1();
        PublicationSequence first = sequences.next();
        PublicationSequence second = sequences.next();

        countries.assign(tshirt.id(), Set.of(new CountryCode("EG"), new CountryCode("SA")), v1.id(), first, BOB);

        assertThat(second).isGreaterThan(first);
        assertThat(countries.findLiveVersion(tshirt.id(), new CountryCode("EG"))).contains(v1.id());
        assertThat(countries.findAll(tshirt.id())).containsOnlyKeys(new CountryCode("EG"), new CountryCode("SA"));
    }

    @Test
    void retentionRemovesDiscardedDraftsAndTheirContent() {
        ProductSetVersion v1 = storedApprovedV1();
        ProductSetVersion v2 = versions.add(v1.branchDraft(versions.nextVersionNumber(tshirt.id()), ALICE));
        v2.putVariation(M, price(99));
        v2 = versions.update(v2);
        v2.discard();
        versions.update(v2);

        int purged = retention.purgeDiscardedCreatedBefore(Instant.now().plusSeconds(60));

        assertThat(purged).isEqualTo(1);
        assertThat(versions.findById(v2.id())).isEmpty();
        assertThat(rows("variation_revision")).isEqualTo(3);
        assertThat(versions.findById(v1.id()).orElseThrow().variations().get(M)).isEqualTo(price(10));
    }
}
