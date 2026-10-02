package com.example.pim.domain.model;

/** The person acting on the catalogue (seller staff, reviewer). */
public record UserId(String value) {

    public UserId {
        value = Require.notBlank(value, "userId");
    }

    @Override
    public String toString() {
        return value;
    }
}
