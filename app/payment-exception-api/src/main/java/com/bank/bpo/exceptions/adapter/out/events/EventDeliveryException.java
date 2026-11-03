package com.bank.bpo.exceptions.adapter.out.events;

/** An event the sink could not deliver now; the outbox tries again. */
public final class EventDeliveryException extends RuntimeException {

    public EventDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
