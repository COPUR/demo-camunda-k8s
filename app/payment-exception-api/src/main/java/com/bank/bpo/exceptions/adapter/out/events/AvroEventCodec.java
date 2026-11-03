package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCaseRaised;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/**
 * The domain events in the published Avro schema. Generic records on purpose: the schema file is the contract (it is
 * what consumers get), and no generated class can drift from it. Each value object maps to one field: the payment
 * id and jurisdiction to strings, the enums to Avro enums with an UNKNOWN default (so an older reader survives a
 * newer symbol), instants to timestamp-millis, ids to uuid strings.
 */
public final class AvroEventCodec {

    public static final String RESOURCE = "avro/payment-exception-event.avsc";
    public static final Schema SCHEMA = load(RESOURCE);
    public static final String CONTENT_TYPE = "application/vnd.apache.avro+binary; encoding=single-object; schema=" + SCHEMA.getFullName();

    private static final Schema ACTOR = SCHEMA.getField("actor").schema();
    private static final Schema RAISED = SCHEMA.getField("payload").schema().getTypes().get(0);
    private static final Schema DECIDED = SCHEMA.getField("payload").schema().getTypes().get(1);

    private final BinaryMessageEncoder<GenericRecord> encoder = new BinaryMessageEncoder<>(GenericData.get(), SCHEMA);
    private final BinaryMessageDecoder<GenericRecord> decoder = new BinaryMessageDecoder<>(GenericData.get(), SCHEMA);

    public byte[] encode(DomainEvent event) {
        try {
            ByteBuffer buffer = encoder.encode(toRecord(event));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("event " + event.eventId() + " does not fit the schema", e);
        }
    }

    public GenericRecord decode(byte[] bytes) {
        try {
            return decoder.decode(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("not a " + SCHEMA.getFullName() + " message", e);
        }
    }

    /** The 64-bit Rabin fingerprint of the schema's canonical form, which every message carries. */
    public static long fingerprint() {
        return SchemaNormalization.parsingFingerprint64(SCHEMA);
    }

    public static String eventType(DomainEvent event) {
        return switch (event) {
            case ExceptionCaseRaised raised -> RAISED.getName();
            case ExceptionDecisionSubmitted decided -> DECIDED.getName();
        };
    }

    static GenericRecord toRecord(DomainEvent event) {
        GenericRecord payload = switch (event) {
            case ExceptionCaseRaised raised -> new GenericRecordBuilder(RAISED)
                .set("jurisdiction", raised.jurisdiction().code())
                .set("rail", symbol(RAISED.getField("rail").schema(), raised.rail().name()))
                .build();
            case ExceptionDecisionSubmitted decided -> new GenericRecordBuilder(DECIDED)
                .set("reviewTaskId", decided.reviewTaskId().value())
                .set("reviewLevel", symbol(DECIDED.getField("reviewLevel").schema(), decided.level().name()))
                .set("queue", decided.queue().map(queue -> queue.value()).orElse(null))
                .set("decision", symbol(DECIDED.getField("decision").schema(), decided.decision().name()))
                .build();
        };
        long workflowInstanceKey = switch (event) {
            case ExceptionCaseRaised raised -> raised.workflowInstanceKey();
            case ExceptionDecisionSubmitted decided -> decided.workflowInstanceKey();
        };
        return new GenericRecordBuilder(SCHEMA)
            .set("eventId", event.eventId().toString())
            .set("occurredAt", event.occurredAt().toEpochMilli())
            .set("paymentId", event.paymentId().value())
            .set("interactionId", event.interactionId().value())
            .set("actor", new GenericRecordBuilder(ACTOR).set("subject", event.actor().subject()).build())
            .set("workflowInstanceKey", workflowInstanceKey)
            .set("payload", payload)
            .build();
    }

    private static GenericData.EnumSymbol symbol(Schema schema, String name) {
        if (!schema.hasEnumSymbol(name)) {
            throw new IllegalArgumentException(schema.getFullName() + " has no symbol " + name);
        }
        return new GenericData.EnumSymbol(schema, name);
    }

    static Schema load(String resource) {
        try (InputStream in = AvroEventCodec.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("schema " + resource + " is missing");
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
