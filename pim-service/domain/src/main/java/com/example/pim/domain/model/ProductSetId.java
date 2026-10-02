package com.example.pim.domain.model;

import java.util.UUID;

public record ProductSetId(UUID value) {

    public ProductSetId {
        Require.notNull(value, "productSetId");
    }

    public static ProductSetId newId() {
        return new ProductSetId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
