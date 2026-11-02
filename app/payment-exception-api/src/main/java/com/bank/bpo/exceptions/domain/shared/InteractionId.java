package com.bank.bpo.exceptions.domain.shared;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The correlation id of one request ({@code x-fapi-interaction-id}, the header the workers already send to the
 * providers). It travels into logs and events so one decision can be followed end to end.
 */
public record InteractionId(String value) {

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public InteractionId {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException("an interaction id is a lower-case RFC 4122 UUID");
        }
    }

    public static InteractionId of(String value) {
        return new InteractionId(value == null ? null : value.toLowerCase(Locale.ROOT));
    }

    /** The caller's id when it is a UUID; anything else is ignored and the caller gets a fresh one back. */
    public static Optional<InteractionId> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return UUID_TEXT.matcher(lower).matches() ? Optional.of(new InteractionId(lower)) : Optional.empty();
    }

    public static InteractionId random() {
        return new InteractionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
