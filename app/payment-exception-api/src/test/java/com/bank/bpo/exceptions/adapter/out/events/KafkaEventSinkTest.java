package com.bank.bpo.exceptions.adapter.out.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventSinkTest {

    final AvroEventCodec codec = new AvroEventCodec();

    @Test
    void sendsTheEventKeyedByPaymentWithRoutingHeaders() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
        KafkaEventSink sink = new KafkaEventSink(producer, "bpo.payment-exception.events.v1", codec, Duration.ofSeconds(1));

        sink.send(Events.raised("demo-tr-unres-611"));

        ProducerRecord<String, byte[]> record = producer.history().getFirst();
        assertThat(record.topic()).isEqualTo("bpo.payment-exception.events.v1");
        assertThat(record.key()).isEqualTo("demo-tr-unres-611");
        assertThat(record.timestamp()).isEqualTo(Events.raised("demo-tr-unres-611").occurredAt().toEpochMilli());
        assertThat(header(record, "event-type")).isEqualTo("ExceptionCaseRaised");
        assertThat(header(record, "event-id")).isEqualTo(Events.raised("demo-tr-unres-611").eventId().toString());
        assertThat(header(record, "content-type")).isEqualTo(AvroEventCodec.CONTENT_TYPE);
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo(Events.INTERACTION.value());
        assertThat(codec.decode(record.value()).get("paymentId").toString()).isEqualTo("demo-tr-unres-611");

        sink.close();
        assertThat(producer.closed()).isTrue();
    }

    @Test
    void aRecordTheBrokerDidNotTakeIsADeliveryFailure() {
        MockProducer<String, byte[]> producer = new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
        KafkaEventSink sink = new KafkaEventSink(producer, "t", codec, Duration.ofMillis(50));

        assertThatThrownBy(() -> sink.send(Events.raised("demo-tr-unres-612"))).isInstanceOf(EventDeliveryException.class)
            .hasMessageContaining("did not take");

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> sink.send(Events.raised("demo-tr-unres-613"))).isInstanceOf(EventDeliveryException.class)
            .hasMessageContaining("interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    static String header(ProducerRecord<String, byte[]> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }
}
