package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * Kafka (Redpanda in the demo): keyed by payment id, so one case's events stay in order on one partition; the
 * producer acknowledges only after all in-sync replicas have the record (acks=all) and is idempotent, so the
 * outbox's retries do not duplicate a record the broker already has. Headers carry what a consumer routes on
 * without decoding: the event type and id, the content type, and the interaction id.
 */
public final class KafkaEventSink implements EventSink, AutoCloseable {

    private final Producer<String, byte[]> producer;
    private final String topic;
    private final AvroEventCodec codec;
    private final Duration sendTimeout;

    public KafkaEventSink(Producer<String, byte[]> producer, String topic, AvroEventCodec codec, Duration sendTimeout) {
        this.producer = producer;
        this.topic = topic;
        this.codec = codec;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void send(DomainEvent event) {
        List<Header> headers = List.of(
            header("event-type", AvroEventCodec.eventType(event)),
            header("event-id", event.eventId().toString()),
            header("content-type", AvroEventCodec.CONTENT_TYPE),
            header("x-fapi-interaction-id", event.interactionId().value()));
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null, event.occurredAt().toEpochMilli(),
            event.paymentId().value(), codec.encode(event), headers);
        try {
            producer.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventDeliveryException("interrupted while sending event " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventDeliveryException("the broker did not take event " + event.eventId(), e);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
