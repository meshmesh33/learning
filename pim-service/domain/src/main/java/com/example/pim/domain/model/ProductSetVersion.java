package com.example.pim.domain.model;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.exception.IllegalVersionStateException;
import com.example.pim.domain.exception.InvalidValueException;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.exception.VariationNotFoundException;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Aggregate root: one version of a product set (template attributes + its variations).
 *
 * <pre>
 *   firstDraft / branchDraft ─► DRAFT ──approve──► APPROVED ──publishTo(countries)──► event
 *                                 └────discard──► DISCARDED
 * </pre>
 *
 * Content can only change while DRAFT. How versions are stored (deduplicated revisions,
 * manifests) is a persistence concern and deliberately invisible here.
 */
public final class ProductSetVersion {

    /** Everything needed to rebuild an aggregate from storage. */
    public record State(
            VersionId id,
            ProductSetId productSetId,
            VersionNumber number,
            VersionStatus status,
            Attributes template,
            Map<VariationKey, Attributes> variations,
            Optional<VersionId> basedOn,
            UserId createdBy,
            Optional<Approval> approval,
            int lockVersion) {

        public State {
            variations = Map.copyOf(Require.notNull(variations, "variations"));
        }
    }

    public record Approval(UserId approvedBy, Instant approvedAt) {
    }

    private final VersionId id;
    private final ProductSetId productSetId;
    private final VersionNumber number;
    private final Optional<VersionId> basedOn;
    private final UserId createdBy;
    private final int lockVersion;
    private final SortedMap<VariationKey, Attributes> variations;
    private VersionStatus status;
    private Attributes template;
    private Optional<Approval> approval;

    private ProductSetVersion(State state) {
        this.id = Require.notNull(state.id(), "id");
        this.productSetId = Require.notNull(state.productSetId(), "productSetId");
        this.number = Require.notNull(state.number(), "number");
        this.status = Require.notNull(state.status(), "status");
        this.template = Require.notNull(state.template(), "template");
        this.variations = new TreeMap<>(Require.notNull(state.variations(), "variations"));
        this.basedOn = Require.notNull(state.basedOn(), "basedOn");
        this.createdBy = Require.notNull(state.createdBy(), "createdBy");
        this.approval = Require.notNull(state.approval(), "approval");
        this.lockVersion = state.lockVersion();
    }

    // ---- factories ---------------------------------------------------------------------------

    public static ProductSetVersion firstDraft(ProductSetId productSetId,
                                               Attributes template,
                                               Map<VariationKey, Attributes> variations,
                                               UserId createdBy) {
        return new ProductSetVersion(new State(VersionId.newId(), productSetId, VersionNumber.FIRST,
                VersionStatus.DRAFT, template, variations, Optional.empty(), createdBy, Optional.empty(), 0));
    }

    public static ProductSetVersion reconstitute(State state) {
        return new ProductSetVersion(state);
    }

    /** Starts the next draft with this approved version's content as the starting point. */
    public ProductSetVersion branchDraft(VersionNumber draftNumber, UserId createdBy) {
        if (status != VersionStatus.APPROVED) {
            throw IllegalVersionStateException.cannotBranchFrom(id, status);
        }
        return new ProductSetVersion(new State(VersionId.newId(), productSetId, draftNumber,
                VersionStatus.DRAFT, template, variations, Optional.of(id), createdBy, Optional.empty(), 0));
    }

    // ---- editing (DRAFT only) ----------------------------------------------------------------

    public void verifyLockVersion(int expectedLockVersion) {
        if (expectedLockVersion != lockVersion) {
            throw new StaleVersionException(id, expectedLockVersion, lockVersion);
        }
    }

    public void replaceTemplate(Attributes newTemplate) {
        requireDraft();
        template = Require.notNull(newTemplate, "template");
    }

    public void putVariation(VariationKey key, Attributes attributes) {
        requireDraft();
        variations.put(Require.notNull(key, "variationKey"), Require.notNull(attributes, "attributes"));
    }

    public void removeVariation(VariationKey key) {
        requireDraft();
        if (variations.remove(key) == null) {
            throw new VariationNotFoundException(id, key);
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    public void approve(UserId approver, Instant at) {
        requireDraft();
        if (variations.isEmpty()) {
            throw new IllegalVersionStateException("Version %s has no variations and cannot be approved".formatted(id));
        }
        status = VersionStatus.APPROVED;
        approval = Optional.of(new Approval(approver, at));
    }

    public void discard() {
        requireDraft();
        status = VersionStatus.DISCARDED;
    }

    /** Only approved content may reach customers. The caller records the country assignment. */
    public ProductSetVersionPublished publishTo(Set<CountryCode> countries, PublicationSequence sequence, Instant at) {
        if (status != VersionStatus.APPROVED) {
            throw IllegalVersionStateException.notPublishable(id, status);
        }
        if (countries == null || countries.isEmpty()) {
            throw new InvalidValueException("At least one country is required to publish");
        }
        return new ProductSetVersionPublished(UUID.randomUUID(), sequence, productSetId, id, number,
                new TreeSet<>(countries), at, template, variations);
    }

    public VersionDiff diffTo(ProductSetVersion other) {
        if (!productSetId.equals(other.productSetId)) {
            throw new InvalidValueException("Cannot diff versions of different product sets");
        }
        return VersionDiff.between(template, variations, other.template, other.variations);
    }

    private void requireDraft() {
        if (status != VersionStatus.DRAFT) {
            throw IllegalVersionStateException.notEditable(id, status);
        }
    }

    // ---- read access -------------------------------------------------------------------------

    public VersionId id() {
        return id;
    }

    public ProductSetId productSetId() {
        return productSetId;
    }

    public VersionNumber number() {
        return number;
    }

    public VersionStatus status() {
        return status;
    }

    public boolean isDraft() {
        return status == VersionStatus.DRAFT;
    }

    public Attributes template() {
        return template;
    }

    public SortedMap<VariationKey, Attributes> variations() {
        return Collections.unmodifiableSortedMap(variations);
    }

    public Optional<VersionId> basedOn() {
        return basedOn;
    }

    public UserId createdBy() {
        return createdBy;
    }

    public Optional<Approval> approval() {
        return approval;
    }

    public int lockVersion() {
        return lockVersion;
    }

    public State state() {
        return new State(id, productSetId, number, status, template, variations(), basedOn, createdBy, approval, lockVersion);
    }
}
