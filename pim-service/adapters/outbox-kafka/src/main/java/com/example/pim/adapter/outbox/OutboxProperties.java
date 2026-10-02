package com.example.pim.adapter.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pim.outbox")
public record OutboxProperties(String topic, int batchSize, long pollIntervalMs) {

    public OutboxProperties {
        if (topic == null || topic.isBlank()) {
            topic = "pim.product-set.published";
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 1000;
        }
    }
}
