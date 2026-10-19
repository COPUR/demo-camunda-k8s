package com.bank.bpo.workerkit;

import java.util.regex.Pattern;

/**
 * Idempotency keys for domain commands (05 section 8, 07 O8): {@code <business key>:<step>[:n]}.
 * The key never contains a process instance key or an element id, so a restarted or re-versioned
 * process asks the domain for the same effect with the same key and gets a replay, not a second effect.
 */
public final class IdempotencyKeys {

    private static final Pattern STEP = Pattern.compile("[a-z][a-z0-9-]{0,40}");

    private IdempotencyKeys() {
    }

    public static String of(String businessKey, String step) {
        Ids.require("business key", businessKey);
        if (step == null || !STEP.matcher(step).matches()) {
            throw new IllegalArgumentException("step is not a kebab-case name");
        }
        return businessKey + ":" + step;
    }

    /** For a money step inside a loop: the counter makes each pass a different effect, a retry the same one. */
    public static String of(String businessKey, String step, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
        return of(businessKey, step) + ":" + attempt;
    }
}
