package com.example.pim.application.port.out;

import com.example.pim.domain.event.ProductSetVersionPublished;

public interface ProductSetEventPublisher {

    /**
     * Must take part in the caller's transaction (e.g. a transactional outbox), so an event is
     * delivered if and only if the country assignment it describes was committed.
     */
    void publish(ProductSetVersionPublished event);
}
