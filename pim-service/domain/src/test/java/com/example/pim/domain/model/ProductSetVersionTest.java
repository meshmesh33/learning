package com.example.pim.domain.model;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.exception.IllegalVersionStateException;
import com.example.pim.domain.exception.InvalidValueException;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.exception.VariationNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSetVersionTest {

    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final VariationKey S = new VariationKey("S");
    private static final VariationKey M = new VariationKey("M");

    private static ProductSetVersion tshirtDraft() {
        return ProductSetVersion.firstDraft(ProductSetId.newId(),
                Attributes.of(Map.of("name", "Basic tee")),
                Map.of(S, Attributes.of(Map.of("price", 10)), M, Attributes.of(Map.of("price", 10))),
                ALICE);
    }

    private static ProductSetVersion approvedTshirt() {
        ProductSetVersion version = tshirtDraft();
        version.approve(BOB, NOW);
        return version;
    }

    @Nested
    class Drafts {

        @Test
        void firstDraftStartsAtVersionOne() {
            ProductSetVersion draft = tshirtDraft();

            assertThat(draft.number()).isEqualTo(VersionNumber.FIRST);
            assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
            assertThat(draft.basedOn()).isEmpty();
        }

        @Test
        void draftContentCanBeEdited() {
            ProductSetVersion draft = tshirtDraft();

            draft.putVariation(new VariationKey("L"), Attributes.of(Map.of("price", 12)));
            draft.removeVariation(S);
            draft.replaceTemplate(Attributes.of(Map.of("name", "Organic tee")));

            assertThat(draft.variations()).containsOnlyKeys(M, new VariationKey("L"));
            assertThat(draft.template().values()).containsEntry("name", "Organic tee");
        }

        @Test
        void removingUnknownVariationFails() {
            assertThatThrownBy(() -> tshirtDraft().removeVariation(new VariationKey("XXL")))
                    .isInstanceOf(VariationNotFoundException.class);
        }

        @Test
        void staleLockVersionIsRejected() {
            assertThatThrownBy(() -> tshirtDraft().verifyLockVersion(3))
                    .isInstanceOf(StaleVersionException.class);
        }
    }

    @Nested
    class Approval {

        @Test
        void approvalFreezesContent() {
            ProductSetVersion version = approvedTshirt();

            assertThat(version.status()).isEqualTo(VersionStatus.APPROVED);
            assertThat(version.approval()).contains(new ProductSetVersion.Approval(BOB, NOW));
            assertThatThrownBy(() -> version.putVariation(S, Attributes.empty()))
                    .isInstanceOf(IllegalVersionStateException.class);
            assertThatThrownBy(() -> version.replaceTemplate(Attributes.empty()))
                    .isInstanceOf(IllegalVersionStateException.class);
            assertThatThrownBy(() -> version.approve(BOB, NOW))
                    .isInstanceOf(IllegalVersionStateException.class);
        }

        @Test
        void versionWithoutVariationsCannotBeApproved() {
            ProductSetVersion draft = ProductSetVersion.firstDraft(ProductSetId.newId(), Attributes.empty(), Map.of(), ALICE);

            assertThatThrownBy(() -> draft.approve(BOB, NOW)).isInstanceOf(IllegalVersionStateException.class);
        }

        @Test
        void discardedDraftCannotBeApproved() {
            ProductSetVersion draft = tshirtDraft();
            draft.discard();

            assertThatThrownBy(() -> draft.approve(BOB, NOW)).isInstanceOf(IllegalVersionStateException.class);
        }
    }

    @Nested
    class Branching {

        @Test
        void nextDraftCopiesApprovedContent() {
            ProductSetVersion v1 = approvedTshirt();

            ProductSetVersion v2 = v1.branchDraft(v1.number().next(), ALICE);

            assertThat(v2.number().value()).isEqualTo(2);
            assertThat(v2.isDraft()).isTrue();
            assertThat(v2.basedOn()).contains(v1.id());
            assertThat(v2.variations()).isEqualTo(v1.variations());
            assertThat(v2.diffTo(v1).isEmpty()).isTrue();
        }

        @Test
        void editingTheBranchLeavesTheOriginalUntouched() {
            ProductSetVersion v1 = approvedTshirt();
            ProductSetVersion v2 = v1.branchDraft(v1.number().next(), ALICE);

            v2.putVariation(M, Attributes.of(Map.of("price", 11)));

            assertThat(v1.variations().get(M).values()).containsEntry("price", 10);
            VersionDiff diff = v1.diffTo(v2);
            assertThat(diff.changedVariations()).containsExactly(M);
            assertThat(diff.templateChanged()).isFalse();
        }

        @Test
        void cannotBranchFromDraft() {
            assertThatThrownBy(() -> tshirtDraft().branchDraft(new VersionNumber(2), ALICE))
                    .isInstanceOf(IllegalVersionStateException.class);
        }
    }

    @Nested
    class Publishing {

        @Test
        void draftIsNeverPublished() {
            assertThatThrownBy(() -> tshirtDraft().publishTo(Set.of(new CountryCode("EG")), new PublicationSequence(1), NOW))
                    .isInstanceOf(IllegalVersionStateException.class);
        }

        @Test
        void approvedVersionProducesEventWithFullSnapshot() {
            ProductSetVersion version = approvedTshirt();

            ProductSetVersionPublished event = version.publishTo(
                    Set.of(new CountryCode("sa"), new CountryCode("EG")), new PublicationSequence(7), NOW);

            assertThat(event.countries()).containsExactly(new CountryCode("EG"), new CountryCode("SA"));
            assertThat(event.sequence()).isEqualTo(new PublicationSequence(7));
            assertThat(event.versionNumber()).isEqualTo(VersionNumber.FIRST);
            assertThat(event.variations()).containsOnlyKeys(M, S);
        }

        @Test
        void requiresAtLeastOneCountry() {
            assertThatThrownBy(() -> approvedTshirt().publishTo(Set.of(), new PublicationSequence(1), NOW))
                    .isInstanceOf(InvalidValueException.class);
        }
    }
}
