package com.bank.bpo.exceptions.application.port.out;

import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;

/**
 * Remembers commands by caller-chosen key, so a retry after a lost response is answered from the record instead of
 * being executed twice.
 */
public interface IdempotencyStore {

    /** Keys are per operation and per caller: one client can neither collide with nor replay another's answers. */
    record Scope(String operation, String principal) {
    }

    sealed interface Claim permits Claim.Acquired, Claim.Replay, Claim.InProgress, Claim.Mismatch, Claim.Full {

        /** The caller runs the command and then completes or releases the claim. */
        record Acquired() implements Claim {
        }

        /** The command ran before with the same request: here is its answer. */
        record Replay(Object result) implements Claim {
        }

        /** The first request with this key has not finished yet. */
        record InProgress() implements Claim {
        }

        /** The key was used for a different request. */
        record Mismatch() implements Claim {
        }

        /** No room for a new record without dropping a running one: the caller comes back later. */
        record Full() implements Claim {
        }
    }

    Claim claim(Scope scope, IdempotencyKey key, String fingerprint);

    void complete(Scope scope, IdempotencyKey key, Object result);

    /** Forgets a claim whose command failed, so the caller may retry with the same key. */
    void release(Scope scope, IdempotencyKey key);
}
