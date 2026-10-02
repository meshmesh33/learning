package com.example.pim.versioning;

import com.example.pim.outbox.OutboxRepository;
import com.example.pim.outbox.ProductSetPublishedEvent;
import com.example.pim.versioning.ProductVersionRepository.VersionRow;
import com.example.pim.versioning.VersioningExceptions.InvalidState;
import com.example.pim.versioning.VersioningExceptions.NotFound;
import com.example.pim.versioning.VersioningExceptions.StaleDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Lifecycle of a product set version:
 *
 * <pre>
 *   create / openDraft ──► DRAFT ──(edit*: new revisions, pointer swaps)──► approve ──► APPROVED
 *                            │                                                          │
 *                            └──► discard ──► DISCARDED                publish(countries) ─┘
 *                                                                (pointer move + outbox, one tx)
 * </pre>
 *
 * Drafts are never visible downstream; only {@link #publish} emits events.
 */
@Service
public class ProductVersionService {

    private final ProductVersionRepository repo;
    private final OutboxRepository outbox;
    private final CanonicalJson json;

    public ProductVersionService(ProductVersionRepository repo, OutboxRepository outbox, CanonicalJson json) {
        this.repo = repo;
        this.outbox = outbox;
        this.json = json;
    }

    // ---- drafts ------------------------------------------------------------------------------

    /** Creates a product set together with its first draft (version 1). */
    @Transactional
    public ProductSetSnapshot createProductSet(String sellerId,
                                               Map<String, Object> template,
                                               Map<String, Map<String, Object>> variations,
                                               String user) {
        UUID productSetId = UUID.randomUUID();
        repo.insertProductSet(productSetId, sellerId);
        long templateRev = repo.upsertTemplateRevision(productSetId, json.canonicalize(template));
        long versionId = repo.insertDraft(productSetId, templateRev, null, user);
        variations.forEach((key, attrs) ->
                repo.putVariation(versionId, key, repo.upsertVariationRevision(productSetId, key, json.canonicalize(attrs))));
        return getVersion(versionId);
    }

    /**
     * Returns the open draft, or starts a new one from the latest approved version. Starting a draft
     * copies only the pointer rows, so it costs the same whether variations hold 1 KB or 1 MB of data.
     */
    @Transactional
    public ProductSetSnapshot openDraft(UUID productSetId, String user) {
        if (!repo.lockProductSet(productSetId)) {
            throw new NotFound("Product set " + productSetId + " not found");
        }
        var existing = repo.findDraft(productSetId);
        if (existing.isPresent()) {
            return getVersion(existing.get().id());
        }
        VersionRow base = repo.findLatestApproved(productSetId)
                .orElseThrow(() -> new InvalidState("Product set " + productSetId + " has no approved version to branch from"));
        long draftId = repo.insertDraft(productSetId, base.templateRevisionId(), base.id(), user);
        repo.copyManifest(base.id(), draftId);
        return getVersion(draftId);
    }

    @Transactional
    public ProductSetSnapshot updateTemplate(long versionId, int lockVersion, Map<String, Object> attributes) {
        VersionRow draft = requireEditableDraft(versionId, lockVersion);
        repo.setTemplate(versionId, repo.upsertTemplateRevision(draft.productSetId(), json.canonicalize(attributes)));
        return getVersion(versionId);
    }

    @Transactional
    public ProductSetSnapshot putVariation(long versionId, int lockVersion, String variationKey, Map<String, Object> attributes) {
        VersionRow draft = requireEditableDraft(versionId, lockVersion);
        long rev = repo.upsertVariationRevision(draft.productSetId(), variationKey, json.canonicalize(attributes));
        repo.putVariation(versionId, variationKey, rev);
        return getVersion(versionId);
    }

    @Transactional
    public ProductSetSnapshot removeVariation(long versionId, int lockVersion, String variationKey) {
        requireEditableDraft(versionId, lockVersion);
        if (!repo.removeVariation(versionId, variationKey)) {
            throw new NotFound("Variation " + variationKey + " not in version " + versionId);
        }
        return getVersion(versionId);
    }

    @Transactional
    public void discard(long versionId, int lockVersion) {
        if (!repo.markDiscarded(versionId, lockVersion)) {
            throw explainDraftFailure(versionId);
        }
    }

    // ---- approval & publication --------------------------------------------------------------

    /** Freezes the draft. Does not publish: countries are assigned explicitly via {@link #publish}. */
    @Transactional
    public ProductSetSnapshot approve(long versionId, int lockVersion, String approver) {
        if (!repo.markApproved(versionId, lockVersion, approver)) {
            throw explainDraftFailure(versionId);
        }
        return getVersion(versionId);
    }

    /**
     * Points the given countries at an approved version and enqueues one event, atomically.
     * Can also be used to roll a country back to an older approved version.
     */
    @Transactional
    public ProductSetPublishedEvent publish(long versionId, List<String> countries, String user) {
        if (countries.isEmpty()) {
            throw new IllegalArgumentException("At least one country is required");
        }
        ProductSetSnapshot snapshot = getVersion(versionId);
        if (snapshot.status() != VersionStatus.APPROVED) {
            throw new InvalidState("Version " + versionId + " is " + snapshot.status() + "; only APPROVED versions can be published");
        }
        List<String> normalized = countries.stream()
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        long seq = repo.nextPublishSeq();
        for (String country : normalized) {
            repo.upsertCountryPointer(snapshot.productSetId(), country, versionId, seq, user);
        }
        ProductSetPublishedEvent event = new ProductSetPublishedEvent(
                UUID.randomUUID(), seq, snapshot.productSetId(), versionId, snapshot.versionNo(),
                normalized, Instant.now(), snapshot.template(), snapshot.variations());
        outbox.insert(snapshot.productSetId(), ProductSetPublishedEvent.TYPE, json.write(event));
        return event;
    }

    // ---- reads -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ProductSetSnapshot getVersion(long versionId) {
        return repo.loadSnapshot(versionId)
                .orElseThrow(() -> new NotFound("Version " + versionId + " not found"));
    }

    /** What a given country currently sells. */
    @Transactional(readOnly = true)
    public ProductSetSnapshot getForCountry(UUID productSetId, String countryCode) {
        long versionId = repo.versionForCountry(productSetId, countryCode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFound("Product set " + productSetId + " is not published in " + countryCode));
        return getVersion(versionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countryPointers(UUID productSetId) {
        return repo.countryPointers(productSetId);
    }

    /** Pointer comparison only; no attribute JSON is read. */
    @Transactional(readOnly = true)
    public VersionDiff diff(long fromVersionId, long toVersionId) {
        VersionRow from = repo.findVersion(fromVersionId).orElseThrow(() -> new NotFound("Version " + fromVersionId + " not found"));
        VersionRow to = repo.findVersion(toVersionId).orElseThrow(() -> new NotFound("Version " + toVersionId + " not found"));
        if (!from.productSetId().equals(to.productSetId())) {
            throw new IllegalArgumentException("Versions belong to different product sets");
        }
        Map<String, Long> a = repo.manifest(fromVersionId);
        Map<String, Long> b = repo.manifest(toVersionId);

        SortedSet<String> added = new TreeSet<>(b.keySet());
        added.removeAll(a.keySet());
        SortedSet<String> removed = new TreeSet<>(a.keySet());
        removed.removeAll(b.keySet());
        Set<String> common = new HashSet<>(a.keySet());
        common.retainAll(b.keySet());
        SortedSet<String> changed = new TreeSet<>();
        for (String key : common) {
            if (!a.get(key).equals(b.get(key))) {
                changed.add(key);
            }
        }
        return new VersionDiff(from.templateRevisionId() != to.templateRevisionId(), added, removed, changed);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private VersionRow requireEditableDraft(long versionId, int lockVersion) {
        if (!repo.bumpDraftLock(versionId, lockVersion)) {
            throw explainDraftFailure(versionId);
        }
        return repo.findVersion(versionId).orElseThrow();
    }

    private RuntimeException explainDraftFailure(long versionId) {
        return repo.findVersion(versionId)
                .<RuntimeException>map(v -> v.status() != VersionStatus.DRAFT
                        ? new InvalidState("Version " + versionId + " is " + v.status() + " and cannot be changed")
                        : new StaleDraft("Version " + versionId + " was modified concurrently (current lockVersion "
                                         + v.lockVersion() + ")"))
                .orElseGet(() -> new NotFound("Version " + versionId + " not found"));
    }
}
