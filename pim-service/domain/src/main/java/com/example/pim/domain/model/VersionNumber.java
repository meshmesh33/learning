package com.example.pim.domain.model;

import com.example.pim.domain.exception.InvalidValueException;

/** Human-facing, per-product-set sequence: v1, v2, v3... */
public record VersionNumber(int value) implements Comparable<VersionNumber> {

    public static final VersionNumber FIRST = new VersionNumber(1);

    public VersionNumber {
        if (value < 1) {
            throw new InvalidValueException("version number must be >= 1, was " + value);
        }
    }

    public VersionNumber next() {
        return new VersionNumber(value + 1);
    }

    @Override
    public int compareTo(VersionNumber other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "v" + value;
    }
}
