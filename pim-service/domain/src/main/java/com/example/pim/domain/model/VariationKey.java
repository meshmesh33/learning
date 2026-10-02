package com.example.pim.domain.model;

import com.example.pim.domain.exception.InvalidValueException;

/** Identifies a variation inside its product set, e.g. "S", "M", "L" or "RED-XL". */
public record VariationKey(String value) implements Comparable<VariationKey> {

    private static final int MAX_LENGTH = 64;

    public VariationKey {
        value = Require.notBlank(value, "variationKey");
        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("variationKey must be at most %d characters".formatted(MAX_LENGTH));
        }
    }

    @Override
    public int compareTo(VariationKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
