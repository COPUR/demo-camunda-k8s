package com.bank.bpo.exceptions.adapter.in.rest.filter;

import java.util.function.LongSupplier;

/** A token bucket: {@code capacity} requests at once, refilled at {@code refillPerSecond}. Thread-safe. */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoTime;
    private double tokens;
    private long refilledAt;

    TokenBucket(int capacity, double refillPerSecond, LongSupplier nanoTime) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("a bucket holds at least one token and refills");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / NANOS_PER_SECOND;
        this.nanoTime = nanoTime;
        this.tokens = capacity;
        this.refilledAt = nanoTime.getAsLong();
    }

    /** Takes a token; returns 0 when granted, otherwise the whole seconds until the next token. */
    synchronized long tryAcquire() {
        long now = nanoTime.getAsLong();
        tokens = Math.min(capacity, tokens + (now - refilledAt) * refillPerNano);
        refilledAt = now;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1 - tokens) / refillPerNano / NANOS_PER_SECOND));
    }
}
