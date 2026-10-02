package com.example.pim.application.service;

import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetEventPublisher;
import com.example.pim.application.port.out.ProductSetRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.application.port.out.PublicationSequenceGenerator;
import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSet;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.ProductSetVersion.State;
import com.example.pim.domain.model.PublicationSequence;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionNumber;
import com.example.pim.domain.model.VersionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Simple fakes of the outbound ports, so use cases are tested without Spring or a database. */
final class InMemoryPorts {

    final ProductSets productSets = new ProductSets();
    final Versions versions = new Versions();
    final CountryAssignments countryAssignments = new CountryAssignments();
    final Sequences sequences = new Sequences();
    final RecordedEvents events = new RecordedEvents();

    static final class ProductSets implements ProductSetRepository {
        private final Map<ProductSetId, ProductSet> store = new HashMap<>();

        @Override
        public void add(ProductSet productSet) {
            store.put(productSet.id(), productSet);
        }

        @Override
        public Optional<ProductSet> findAndLock(ProductSetId id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    /** Stores immutable {@link State} copies so callers cannot mutate what is "persisted". */
    static final class Versions implements ProductSetVersionRepository {
        private final Map<VersionId, State> store = new HashMap<>();

        @Override
        public Optional<ProductSetVersion> findById(VersionId id) {
            return Optional.ofNullable(store.get(id)).map(ProductSetVersion::reconstitute);
        }

        @Override
        public Optional<ProductSetVersion> findDraft(ProductSetId productSetId) {
            return ofProductSet(productSetId)
                    .filter(s -> s.status() == VersionStatus.DRAFT)
                    .findFirst()
                    .map(ProductSetVersion::reconstitute);
        }

        @Override
        public Optional<ProductSetVersion> findLatestApproved(ProductSetId productSetId) {
            return ofProductSet(productSetId)
                    .filter(s -> s.status() == VersionStatus.APPROVED)
                    .max(Comparator.comparing(State::number))
                    .map(ProductSetVersion::reconstitute);
        }

        @Override
        public VersionNumber nextVersionNumber(ProductSetId productSetId) {
            return ofProductSet(productSetId)
                    .map(State::number)
                    .max(Comparator.naturalOrder())
                    .map(VersionNumber::next)
                    .orElse(VersionNumber.FIRST);
        }

        @Override
        public ProductSetVersion add(ProductSetVersion version) {
            store.put(version.id(), version.state());
            return ProductSetVersion.reconstitute(version.state());
        }

        @Override
        public ProductSetVersion update(ProductSetVersion version) {
            State current = store.get(version.id());
            if (current == null || current.lockVersion() != version.lockVersion()) {
                throw new StaleVersionException(version.id());
            }
            State s = version.state();
            State saved = new State(s.id(), s.productSetId(), s.number(), s.status(), s.template(), s.variations(),
                    s.basedOn(), s.createdBy(), s.approval(), s.lockVersion() + 1);
            store.put(saved.id(), saved);
            return ProductSetVersion.reconstitute(saved);
        }

        int count() {
            return store.size();
        }

        private java.util.stream.Stream<State> ofProductSet(ProductSetId productSetId) {
            return store.values().stream().filter(s -> s.productSetId().equals(productSetId));
        }
    }

    static final class CountryAssignments implements CountryAssignmentRepository {
        private final Map<ProductSetId, SortedMap<CountryCode, VersionId>> store = new HashMap<>();

        @Override
        public void assign(ProductSetId productSetId, Set<CountryCode> countries, VersionId versionId,
                           PublicationSequence sequence, UserId assignedBy) {
            SortedMap<CountryCode, VersionId> assignments = store.computeIfAbsent(productSetId, id -> new TreeMap<>());
            countries.forEach(country -> assignments.put(country, versionId));
        }

        @Override
        public Optional<VersionId> findLiveVersion(ProductSetId productSetId, CountryCode country) {
            return Optional.ofNullable(findAll(productSetId).get(country));
        }

        @Override
        public SortedMap<CountryCode, VersionId> findAll(ProductSetId productSetId) {
            return new TreeMap<>(store.getOrDefault(productSetId, new TreeMap<>()));
        }
    }

    static final class Sequences implements PublicationSequenceGenerator {
        private final AtomicLong next = new AtomicLong();

        @Override
        public PublicationSequence next() {
            return new PublicationSequence(next.incrementAndGet());
        }
    }

    static final class RecordedEvents implements ProductSetEventPublisher {
        final List<ProductSetVersionPublished> published = new ArrayList<>();

        @Override
        public void publish(ProductSetVersionPublished event) {
            published.add(event);
        }
    }
}
