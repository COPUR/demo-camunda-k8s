package com.bank.bpo.exceptions.domain.shared;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Event ids derived from the business fact they record, so a retried publication carries the same id and a
 * consumer drops the duplicate (at-least-once delivery, exactly-once processing by id).
 */
public final class EventId {

    private EventId() {
    }

    /** A name-based UUID over the length-prefixed parts, so "a|b" + "c" never collides with "a" + "b|c". */
    public static UUID forFact(String... parts) {
        StringBuilder name = new StringBuilder("bpo.exceptions/");
        for (String part : parts) {
            name.append(part.length()).append(':').append(part).append(';');
        }
        return UUID.nameUUIDFromBytes(name.toString().getBytes(StandardCharsets.UTF_8));
    }
}
