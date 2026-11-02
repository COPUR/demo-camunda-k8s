package com.bank.bpo.exceptions.application.port.out;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;

/**
 * Hands an event to the outbox. Returns at once: delivery is the adapter's job (at least once, ordered per payment,
 * retried with backoff), so a slow broker never slows a decision down.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
