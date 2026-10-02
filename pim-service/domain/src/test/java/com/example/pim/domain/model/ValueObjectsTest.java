package com.example.pim.domain.model;

import com.example.pim.domain.exception.InvalidValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectsTest {

    @Test
    void countryCodeIsNormalised() {
        assertThat(new CountryCode(" eg ").value()).isEqualTo("EG");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "E", "EGY", "1A"})
    void invalidCountryCodesAreRejected(String raw) {
        assertThatThrownBy(() -> new CountryCode(raw)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void attributesAreDeeplyImmutable() {
        Map<String, Object> nested = new HashMap<>(Map.of("origin", "EG"));
        List<Object> tags = new ArrayList<>(List.of("summer"));
        Attributes attributes = Attributes.of(Map.of("brand", nested, "tags", tags));

        nested.put("origin", "TR");
        tags.add("sale");

        assertThat(attributes.values().get("brand")).isEqualTo(Map.of("origin", "EG"));
        assertThat(attributes.values().get("tags")).isEqualTo(List.of("summer"));
        assertThatThrownBy(() -> attributes.values().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void attributesCompareByValueRegardlessOfKeyOrder() {
        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        Map<String, Object> second = new java.util.LinkedHashMap<>();
        second.put("b", 2);
        second.put("a", 1);

        assertThat(Attributes.of(first)).isEqualTo(Attributes.of(second));
    }

    @Test
    void versionNumberMustBePositive() {
        assertThatThrownBy(() -> new VersionNumber(0)).isInstanceOf(InvalidValueException.class);
        assertThat(VersionNumber.FIRST.next()).isEqualTo(new VersionNumber(2));
    }
}
