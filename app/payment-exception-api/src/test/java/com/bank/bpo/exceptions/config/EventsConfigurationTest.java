package com.bank.bpo.exceptions.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.bpo.exceptions.adapter.out.events.AvroEventCodec;
import com.bank.bpo.exceptions.adapter.out.events.EventSink;
import com.bank.bpo.exceptions.adapter.out.events.KafkaEventSink;
import com.bank.bpo.exceptions.adapter.out.events.LoggingEventSink;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

class EventsConfigurationTest {

    static ExceptionApiProperties properties(String bootstrapServers) {
        return new ExceptionApiProperties(Set.of("TR"),
            new ExceptionApiProperties.Workflow("pay-instant-exception", Duration.ofSeconds(5), Duration.ofMinutes(2)),
            new ExceptionApiProperties.Idempotency(Duration.ofHours(1), 10),
            new ExceptionApiProperties.Http(1024, new ExceptionApiProperties.RateLimit(1, 1, 1)),
            new ExceptionApiProperties.Security("https://issuer.test", "https://issuer.test/certs", "payment-exception-api", "preferred_username",
                "groups", null),
            new ExceptionApiProperties.Events("bpo.payment-exception.events.v1",
                new ExceptionApiProperties.Kafka(bootstrapServers, Map.of("security.protocol", "SSL")),
                new ExceptionApiProperties.Outbox(10, Duration.ofMillis(100), Duration.ofSeconds(1))));
    }

    @Test
    void withoutABrokerEventsGoToTheLog() {
        assertThat(new EventsConfiguration().eventSink(properties(""), new AvroEventCodec())).isInstanceOf(LoggingEventSink.class);
    }

    @Test
    void withABrokerEventsGoToKafkaDurablyAndInOrder() throws Exception {
        EventSink sink = new EventsConfiguration().eventSink(properties("127.0.0.1:1"), new AvroEventCodec());
        assertThat(sink).isInstanceOf(KafkaEventSink.class);
        ((KafkaEventSink) sink).close();

        Map<String, Object> config = EventsConfiguration.producerConfig(properties("127.0.0.1:1").events().kafka());
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
            .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
            .containsEntry("security.protocol", "SSL");
    }
}
