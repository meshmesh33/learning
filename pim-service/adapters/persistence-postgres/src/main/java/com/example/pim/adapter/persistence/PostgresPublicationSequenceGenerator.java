package com.example.pim.adapter.persistence;

import com.example.pim.application.port.out.PublicationSequenceGenerator;
import com.example.pim.domain.model.PublicationSequence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class PostgresPublicationSequenceGenerator implements PublicationSequenceGenerator {

    private final JdbcClient jdbc;

    PostgresPublicationSequenceGenerator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PublicationSequence next() {
        return new PublicationSequence(jdbc.sql("SELECT nextval('publish_seq')").query(Long.class).single());
    }
}
