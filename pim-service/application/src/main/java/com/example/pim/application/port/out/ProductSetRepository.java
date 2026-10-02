package com.example.pim.application.port.out;

import com.example.pim.domain.model.ProductSet;
import com.example.pim.domain.model.ProductSetId;

import java.util.Optional;

public interface ProductSetRepository {

    void add(ProductSet productSet);

    /**
     * Loads the product set and holds an exclusive lock on it until the current transaction ends,
     * so concurrent requests cannot open two drafts at once.
     */
    Optional<ProductSet> findAndLock(ProductSetId id);
}
