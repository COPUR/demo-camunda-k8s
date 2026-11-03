package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore.Claim;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore.Scope;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.function.Supplier;

/** Runs a command at most once per idempotency key; the one retry layer of a command is the caller's. */
final class IdempotentCommand {

    record Ran<T>(T result, boolean replayed) {
    }

    private IdempotentCommand() {
    }

    static <T> Ran<T> run(IdempotencyStore store, Scope scope, IdempotencyKey key, String fingerprint, Class<T> type, Supplier<T> command) {
        switch (store.claim(scope, key, fingerprint)) {
            case Claim.Replay replay -> {
                return new Ran<>(type.cast(replay.result()), true);
            }
            case Claim.InProgress ignored ->
                throw new DomainException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS, "the first request with this Idempotency-Key is still running");
            case Claim.Mismatch ignored ->
                throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "this Idempotency-Key was used for a different request");
            case Claim.Full ignored -> throw new DomainException(ErrorCode.RATE_LIMITED, "too many requests in flight");
            case Claim.Acquired ignored -> {
                // run below
            }
        }
        T result;
        try {
            result = command.get();
        } catch (RuntimeException e) {
            store.release(scope, key);
            throw e;
        }
        store.complete(scope, key, result);
        return new Ran<>(result, false);
    }
}
