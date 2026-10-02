package com.example.pim.domain.model;

/**
 * Global, strictly increasing number stamped on every publication. Consumers order events by it,
 * not by {@link VersionNumber}: rolling a country back to v1 is a newer decision than publishing v2.
 */
public record PublicationSequence(long value) implements Comparable<PublicationSequence> {

    @Override
    public int compareTo(PublicationSequence other) {
        return Long.compare(value, other.value);
    }
}
