package com.example.pim.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free-form, deeply immutable attribute bag (name, material, price, size...). Equality is by value,
 * which is what lets two versions be compared and identical content be stored once.
 */
public record Attributes(Map<String, Object> values) {

    private static final Attributes EMPTY = new Attributes(Map.of());

    public Attributes {
        values = immutableCopy(Require.notNull(values, "attributes"));
    }

    public static Attributes of(Map<String, ?> values) {
        return new Attributes(Collections.unmodifiableMap(values));
    }

    public static Attributes empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    private static Map<String, Object> immutableCopy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableCopy(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(element -> copy.add(immutableValue(element)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
