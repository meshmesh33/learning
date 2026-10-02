package com.example.pim.application.service;

import com.example.pim.application.port.in.PublishVersionUseCase;
import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetEventPublisher;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.application.port.out.PublicationSequenceGenerator;
import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.model.ProductSetVersion;

import java.time.Clock;

/**
 * Runs in one transaction: the country assignment and the outgoing event are committed together
 * or not at all.
 */
public class PublishVersionService implements PublishVersionUseCase {

    private final VersionLookup lookup;
    private final CountryAssignmentRepository countryAssignments;
    private final PublicationSequenceGenerator sequences;
    private final ProductSetEventPublisher events;
    private final Clock clock;

    public PublishVersionService(ProductSetVersionRepository versions,
                                 CountryAssignmentRepository countryAssignments,
                                 PublicationSequenceGenerator sequences,
                                 ProductSetEventPublisher events,
                                 Clock clock) {
        this.lookup = new VersionLookup(versions);
        this.countryAssignments = countryAssignments;
        this.sequences = sequences;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public ProductSetVersionPublished publish(PublishVersionCommand command) {
        ProductSetVersion version = lookup.load(command.versionId());
        ProductSetVersionPublished published = version.publishTo(command.countries(), sequences.next(), clock.instant());

        countryAssignments.assign(version.productSetId(), published.countries(), version.id(),
                published.sequence(), command.publishedBy());
        events.publish(published);
        return published;
    }
}
