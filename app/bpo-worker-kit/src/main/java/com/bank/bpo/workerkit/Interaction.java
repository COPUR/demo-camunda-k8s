package com.bank.bpo.workerkit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The x-fapi-interaction-id of the provider calls a job makes. It is derived from the business key, so every
 * call for one application (every step, every retry, every provider) carries the same id and one onboarding
 * can be followed across services. Job handlers run on the worker thread, so a thread-local is enough.
 */
public final class Interaction {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Interaction() {
    }

    public static String idFor(String businessKey) {
        return UUID.nameUUIDFromBytes(("bpo:" + businessKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static <T> T within(String businessKey, Supplier<T> call) {
        String previous = CURRENT.get();
        CURRENT.set(idFor(Ids.require("business key", businessKey)));
        try {
            return call.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The current job's id, or a fresh one for a call made outside a job (tests, the token endpoint). */
    static String current() {
        String id = CURRENT.get();
        return id != null ? id : UUID.randomUUID().toString();
    }
}
