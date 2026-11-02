package com.bank.bpo.exceptions.adapter.out.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

class AvroEventCodecTest {

    final AvroEventCodec codec = new AvroEventCodec();

    @Test
    void aRaisedCaseRoundTripsThroughTheSchema() {
        GenericRecord record = codec.decode(codec.encode(Events.raised("demo-tr-unres-601")));

        assertThat(record.get("paymentId").toString()).isEqualTo("demo-tr-unres-601");
        assertThat(record.get("eventId").toString()).isEqualTo(Events.raised("demo-tr-unres-601").eventId().toString());
        assertThat(record.get("occurredAt")).isEqualTo(1793613600123L);
        assertThat(record.get("interactionId").toString()).isEqualTo(Events.INTERACTION.value());
        assertThat(((GenericRecord) record.get("actor")).get("subject").toString()).isEqualTo("svc-bpo-worker-payments");
        assertThat(record.get("workflowInstanceKey")).isEqualTo(2251799813685300L);
        GenericRecord payload = (GenericRecord) record.get("payload");
        assertThat(payload.getSchema().getName()).isEqualTo("ExceptionCaseRaised");
        assertThat(payload.get("jurisdiction").toString()).isEqualTo("TR");
        assertThat(payload.get("rail").toString()).isEqualTo("FAST");
    }

    @Test
    void aDecisionRoundTripsWithAndWithoutItsQueue() {
        GenericRecord withQueue = (GenericRecord) codec.decode(codec.encode(Events.decided(Optional.of(QueueName.of("pay-tr-fast-supervisor")))))
            .get("payload");
        assertThat(withQueue.getSchema().getName()).isEqualTo("ExceptionDecisionSubmitted");
        assertThat(withQueue.get("queue").toString()).isEqualTo("pay-tr-fast-supervisor");
        assertThat(withQueue.get("reviewLevel").toString()).isEqualTo("SUPERVISOR");
        assertThat(withQueue.get("decision").toString()).isEqualTo("REJECTED");
        assertThat(withQueue.get("reviewTaskId")).isEqualTo(2251799813685400L);

        GenericRecord withoutQueue = (GenericRecord) codec.decode(codec.encode(Events.decided(Optional.empty()))).get("payload");
        assertThat(withoutQueue.get("queue")).isNull();
        assertThat(AvroEventCodec.eventType(Events.decided(Optional.empty()))).isEqualTo("ExceptionDecisionSubmitted");
    }

    @Test
    void everyMessageCarriesTheSingleObjectHeaderAndTheSchemaFingerprint() {
        byte[] bytes = codec.encode(Events.raised("demo-tr-unres-602"));
        assertThat(bytes[0]).isEqualTo((byte) 0xC3);
        assertThat(bytes[1]).isEqualTo((byte) 0x01);
        assertThat(ByteBuffer.wrap(bytes, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()).isEqualTo(AvroEventCodec.fingerprint());
        assertThat(AvroEventCodec.CONTENT_TYPE).contains("single-object").contains("com.bank.bpo.exceptions.events.v1.PaymentExceptionEvent");
    }

    @Test
    void theSameFactAlwaysHasTheSameEventId() {
        assertThat(Events.raised("demo-tr-unres-603").eventId()).isEqualTo(Events.raised("demo-tr-unres-603").eventId())
            .isNotEqualTo(Events.raised("demo-tr-unres-604").eventId());
    }

    @Test
    void refusesBytesThatAreNotThisSchemasMessage() {
        assertThatThrownBy(() -> codec.decode(new byte[] {1, 2, 3})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AvroEventCodec.load("avro/missing.avsc")).isInstanceOf(IllegalStateException.class);
    }

    /**
     * The published schema evolves compatibly in both directions: consumers on the released schema read new events
     * (forward), and new consumers read old events (backward). A change that breaks either is a new topic version.
     */
    @Test
    void theSchemaStaysCompatibleWithTheReleasedOneBothWays() {
        Schema released = AvroEventCodec.load("avro/baseline/payment-exception-event-1.0.avsc");
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(AvroEventCodec.SCHEMA, released).getType())
            .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(released, AvroEventCodec.SCHEMA).getType())
            .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    void enumsLetAnOlderReaderSurviveANewSymbol() {
        Schema newer = new Schema.Parser().parse(AvroEventCodec.SCHEMA.toString().replace("\"symbols\":[\"UNKNOWN\",\"FAST\"]",
            "\"symbols\":[\"UNKNOWN\",\"FAST\",\"EFT\"]"));
        assertThat(newer.toString()).contains("EFT");
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(AvroEventCodec.SCHEMA, newer).getType())
            .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }
}
