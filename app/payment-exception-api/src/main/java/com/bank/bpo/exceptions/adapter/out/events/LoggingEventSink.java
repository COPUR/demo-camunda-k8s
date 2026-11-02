package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Without a broker (the default demo), events still go through the schema and are logged by type, id and size,
 * never by content: the log is not an event store.
 */
public final class LoggingEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventSink.class);

    private final AvroEventCodec codec;

    public LoggingEventSink(AvroEventCodec codec) {
        this.codec = codec;
    }

    @Override
    public void send(DomainEvent event) {
        int size = codec.encode(event).length;
        log.info("event {} {} for payment {} ({} bytes, no broker configured)", AvroEventCodec.eventType(event), event.eventId(),
            event.paymentId(), size);
    }
}
