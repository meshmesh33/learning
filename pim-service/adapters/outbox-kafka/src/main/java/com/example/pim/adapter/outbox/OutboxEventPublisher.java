package com.example.pim.adapter.outbox;

import com.example.pim.application.port.out.ProductSetEventPublisher;
import com.example.pim.domain.event.ProductSetVersionPublished;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Implements the outbound port by appending to the outbox inside the caller's transaction. */
@Component
class OutboxEventPublisher implements ProductSetEventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    OutboxEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ProductSetVersionPublished event) {
        ProductSetPublishedMessage message = ProductSetPublishedMessage.from(event);
        outbox.append(message.productSetId(), ProductSetPublishedMessage.TYPE, toJson(message));
    }

    private String toJson(ProductSetPublishedMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + message.eventId(), e);
        }
    }
}
