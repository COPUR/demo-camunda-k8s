package com.bank.bpo.exceptions.adapter.out.events;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class LoggingEventSinkTest {

    @Test
    void encodesAndLogsWithoutABroker() {
        assertThatCode(() -> new LoggingEventSink(new AvroEventCodec()).send(Events.raised("demo-tr-unres-631"))).doesNotThrowAnyException();
    }
}
