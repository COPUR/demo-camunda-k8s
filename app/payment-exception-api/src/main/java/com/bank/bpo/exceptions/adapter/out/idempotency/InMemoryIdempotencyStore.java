package com.bank.bpo.exceptions.adapter.out.idempotency;

import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Idempotency records in this process's memory, bounded in count and age. Good for one replica; with more than one,
 * the records belong in a shared store (DynamoDB with a conditional put, or a unique key in the database), behind the
 * same port. When full, the oldest finished record is dropped first; a running one never is.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Key(Scope scope, String value) {
    }

    private record Entry(String fingerprint, Instant createdAt, boolean done, Object result) {
    }

    private final Map<Key, Entry> entries = new HashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    public InMemoryIdempotencyStore(Duration ttl, int maxEntries, Clock clock) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("the time to live is positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("the store holds at least one record");
        }
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @Override
    public synchronized Claim claim(Scope scope, IdempotencyKey key, String fingerprint) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.createdAt().plus(ttl).isAfter(now));
        Key id = new Key(scope, key.value());
        Entry existing = entries.get(id);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                return new Claim.Mismatch();
            }
            return existing.done() ? new Claim.Replay(existing.result()) : new Claim.InProgress();
        }
        if (entries.size() >= maxEntries && !dropOldestFinished()) {
            return new Claim.Full();
        }
        entries.put(id, new Entry(fingerprint, now, false, null));
        return new Claim.Acquired();
    }

    @Override
    public synchronized void complete(Scope scope, IdempotencyKey key, Object result) {
        entries.computeIfPresent(new Key(scope, key.value()), (k, entry) -> new Entry(entry.fingerprint(), entry.createdAt(), true, result));
    }

    @Override
    public synchronized void release(Scope scope, IdempotencyKey key) {
        entries.remove(new Key(scope, key.value()));
    }

    synchronized int size() {
        return entries.size();
    }

    private boolean dropOldestFinished() {
        return entries.entrySet().stream().filter(e -> e.getValue().done())
            .min(Comparator.comparing((Map.Entry<Key, Entry> e) -> e.getValue().createdAt()))
            .map(oldest -> entries.remove(oldest.getKey()) != null)
            .orElse(false);
    }
}
