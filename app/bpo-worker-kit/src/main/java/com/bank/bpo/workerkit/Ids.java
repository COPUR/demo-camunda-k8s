package com.bank.bpo.workerkit;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one check for every id a worker puts into a URL path or an idempotency key, and for every id or enum a
 * provider returns. Process variables come from start data the worker does not control, so an id is a plain
 * token: letters, digits, "_" and "-", at most 64 characters. No dot, slash or percent sign can pass, so no id
 * can add or climb a path segment on a call that carries the worker's service token.
 */
public final class Ids {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Ids() {
    }

    /** {@code value} as an id, or a CONTRACT failure (an incident, no call made) naming only the field. */
    public static String require(String field, Object value) {
        if (!(value instanceof String s) || !ID.matcher(s).matches()) {
            throw new ProviderException(ProviderException.Kind.CONTRACT, "INVALID_ID", field + " is not a plain id", null);
        }
        return s;
    }

    /** {@code value} as one of {@code allowed}, or a CONTRACT failure naming only the field. */
    public static String oneOf(String field, Object value, Set<String> allowed) {
        if (!(value instanceof String s) || !allowed.contains(s)) {
            throw new ProviderException(ProviderException.Kind.CONTRACT, "INVALID_VALUE", field + " is not one of " + allowed, null);
        }
        return s;
    }
}
