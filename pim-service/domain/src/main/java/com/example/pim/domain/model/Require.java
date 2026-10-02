package com.example.pim.domain.model;

import com.example.pim.domain.exception.InvalidValueException;

/** Small guard helpers so value objects read as a list of rules. */
final class Require {

    private Require() {
    }

    static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new InvalidValueException(name + " must not be null");
        }
        return value;
    }

    static String notBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException(name + " must not be blank");
        }
        return value.strip();
    }
}
