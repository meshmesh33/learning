package com.example.pim.domain.model;

import com.example.pim.domain.exception.InvalidValueException;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISO 3166-1 alpha-2 country code, normalised to upper case. */
public record CountryCode(String value) implements Comparable<CountryCode> {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");

    public CountryCode {
        value = Require.notBlank(value, "countryCode").toUpperCase(Locale.ROOT);
        if (!ALPHA_2.matcher(value).matches()) {
            throw new InvalidValueException("countryCode must be two letters, was '%s'".formatted(value));
        }
    }

    @Override
    public int compareTo(CountryCode other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
