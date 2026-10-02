package com.example.pim.domain.model;

/** The stable identity a seller's product line hangs off; its content lives in versions. */
public record ProductSet(ProductSetId id, SellerId sellerId) {

    public ProductSet {
        Require.notNull(id, "id");
        Require.notNull(sellerId, "sellerId");
    }

    public static ProductSet register(SellerId sellerId) {
        return new ProductSet(ProductSetId.newId(), sellerId);
    }
}
