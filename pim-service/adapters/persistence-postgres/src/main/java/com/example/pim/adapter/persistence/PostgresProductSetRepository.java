package com.example.pim.adapter.persistence;

import com.example.pim.application.port.out.ProductSetRepository;
import com.example.pim.domain.model.ProductSet;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.SellerId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class PostgresProductSetRepository implements ProductSetRepository {

    private final JdbcClient jdbc;

    PostgresProductSetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(ProductSet productSet) {
        jdbc.sql("INSERT INTO product_set (id, seller_id) VALUES (?, ?)")
                .params(productSet.id().value(), productSet.sellerId().value())
                .update();
    }

    @Override
    public Optional<ProductSet> findAndLock(ProductSetId id) {
        return jdbc.sql("SELECT seller_id FROM product_set WHERE id = ? FOR UPDATE")
                .param(id.value())
                .query(String.class)
                .optional()
                .map(sellerId -> new ProductSet(id, new SellerId(sellerId)));
    }
}
