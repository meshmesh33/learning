package com.example.pim.application.service;

import com.example.pim.application.exception.ResourceNotFoundException;
import com.example.pim.application.port.in.CreateProductSetUseCase.CreateProductSetCommand;
import com.example.pim.application.port.in.EditDraftUseCase.PutVariationCommand;
import com.example.pim.application.port.in.EditDraftUseCase.ReplaceTemplateCommand;
import com.example.pim.application.port.in.OpenDraftUseCase.OpenDraftCommand;
import com.example.pim.application.port.in.PublishVersionUseCase.PublishVersionCommand;
import com.example.pim.application.port.in.ReviewVersionUseCase.ApproveVersionCommand;
import com.example.pim.application.port.in.ReviewVersionUseCase.DiscardDraftCommand;
import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.exception.IllegalVersionStateException;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.SellerId;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSetUseCasesTest {

    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final CountryCode EG = new CountryCode("EG");
    private static final CountryCode SA = new CountryCode("SA");
    private static final VariationKey M = new VariationKey("M");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryPorts ports = new InMemoryPorts();

    private final CreateProductSetService create = new CreateProductSetService(ports.productSets, ports.versions);
    private final OpenDraftService openDraft = new OpenDraftService(ports.productSets, ports.versions);
    private final EditDraftService edit = new EditDraftService(ports.versions);
    private final ReviewVersionService review = new ReviewVersionService(ports.versions, clock);
    private final PublishVersionService publish = new PublishVersionService(
            ports.versions, ports.countryAssignments, ports.sequences, ports.events, clock);
    private final ProductSetQueryService queries = new ProductSetQueryService(ports.versions, ports.countryAssignments);

    private ProductSetVersion createTshirt() {
        return create.create(new CreateProductSetCommand(new SellerId("seller-1"),
                Attributes.of(Map.of("name", "Basic tee")),
                Map.of(new VariationKey("S"), Attributes.of(Map.of("price", 10)), M, Attributes.of(Map.of("price", 10))),
                ALICE));
    }

    private ProductSetVersion approve(ProductSetVersion draft) {
        return review.approve(new ApproveVersionCommand(draft.id(), draft.lockVersion(), BOB));
    }

    private ProductSetVersionPublished publish(ProductSetVersion version, CountryCode... countries) {
        return publish.publish(new PublishVersionCommand(version.id(), Set.of(countries), BOB));
    }

    @Test
    void createsFirstDraftWithoutNotifyingAnyone() {
        ProductSetVersion v1 = createTshirt();

        assertThat(v1.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(ports.events.published).isEmpty();
        assertThatThrownBy(() -> queries.liveVersion(v1.productSetId(), EG))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void approvedVersionIsPublishedPerCountry() {
        ProductSetVersion v1 = approve(createTshirt());
        publish(v1, EG, SA);

        ProductSetVersion v2 = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE));
        v2 = edit.putVariation(new PutVariationCommand(v2.id(), v2.lockVersion(), M, Attributes.of(Map.of("price", 11))));
        v2 = approve(v2);
        publish(v2, EG);

        assertThat(queries.liveVersion(v1.productSetId(), EG).number().value()).isEqualTo(2);
        assertThat(queries.liveVersion(v1.productSetId(), SA).number().value()).isEqualTo(1);
        assertThat(queries.diff(v1.id(), v2.id()).changedVariations()).containsExactly(M);
        assertThat(ports.events.published).hasSize(2);
    }

    @Test
    void rollbackIsANewerPublication() {
        ProductSetVersion v1 = approve(createTshirt());
        ProductSetVersionPublished first = publish(v1, EG);
        ProductSetVersion v2 = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE));
        v2 = approve(edit.replaceTemplate(new ReplaceTemplateCommand(v2.id(), v2.lockVersion(),
                Attributes.of(Map.of("name", "Organic tee")))));
        ProductSetVersionPublished second = publish(v2, EG);

        ProductSetVersionPublished rollback = publish(v1, EG);

        assertThat(rollback.sequence()).isGreaterThan(second.sequence()).isGreaterThan(first.sequence());
        assertThat(rollback.versionNumber()).isLessThan(second.versionNumber());
        assertThat(queries.liveVersion(v1.productSetId(), EG).id()).isEqualTo(v1.id());
    }

    @Test
    void draftCannotBePublished() {
        ProductSetVersion draft = createTshirt();

        assertThatThrownBy(() -> publish(draft, EG)).isInstanceOf(IllegalVersionStateException.class);
        assertThat(ports.events.published).isEmpty();
    }

    @Test
    void openDraftReturnsTheExistingDraft() {
        ProductSetVersion v1 = approve(createTshirt());

        ProductSetVersion first = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE));
        ProductSetVersion second = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), BOB));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(ports.versions.count()).isEqualTo(2);
    }

    @Test
    void versionNumbersAreNotReusedAfterDiscard() {
        ProductSetVersion v1 = approve(createTshirt());
        ProductSetVersion v2 = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE));
        review.discard(new DiscardDraftCommand(v2.id(), v2.lockVersion()));

        ProductSetVersion v3 = openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE));

        assertThat(v3.number().value()).isEqualTo(3);
        assertThat(v3.basedOn()).contains(v1.id());
    }

    @Test
    void openDraftNeedsAnApprovedVersion() {
        ProductSetVersion v1 = createTshirt();
        review.discard(new DiscardDraftCommand(v1.id(), v1.lockVersion()));

        assertThatThrownBy(() -> openDraft.openDraft(new OpenDraftCommand(v1.productSetId(), ALICE)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> openDraft.openDraft(new OpenDraftCommand(ProductSetId.newId(), ALICE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void staleEditIsRejected() {
        ProductSetVersion v1 = createTshirt();
        int readLockVersion = v1.lockVersion();
        edit.putVariation(new PutVariationCommand(v1.id(), readLockVersion, M, Attributes.of(Map.of("price", 9))));

        assertThatThrownBy(() -> edit.putVariation(
                new PutVariationCommand(v1.id(), readLockVersion, M, Attributes.of(Map.of("price", 8)))))
                .isInstanceOf(StaleVersionException.class);
    }
}
