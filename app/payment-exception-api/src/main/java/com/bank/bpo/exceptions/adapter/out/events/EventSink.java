package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;

/** Where the outbox delivers to. {@code send} returns once the event is durable there, or throws. */
public interface EventSink {

    void send(DomainEvent event);
}
