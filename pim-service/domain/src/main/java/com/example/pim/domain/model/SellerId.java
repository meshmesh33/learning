package com.example.pim.domain.model;

public record SellerId(String value) {

    public SellerId {
        value = Require.notBlank(value, "sellerId");
    }

    @Override
    public String toString() {
        return value;
    }
}
