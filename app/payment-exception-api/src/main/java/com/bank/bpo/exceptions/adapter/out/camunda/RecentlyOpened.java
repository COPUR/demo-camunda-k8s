package com.bank.bpo.exceptions.adapter.out.camunda;

import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-your-writes over an eventually consistent search: the engine's search API shows a new instance only after
 * its exporter caught up, so the cases this replica opened during the last window are remembered here. Bounded, and
 * per replica: a client that reads through another replica in that window retries.
 */
final class RecentlyOpened {

    record Entry(NewExceptionCase request, long workflowInstanceKey, Instant openedAt) {
    }

    private final Duration window;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<PaymentId, Entry> entries = new ConcurrentHashMap<>();

    RecentlyOpened(Duration window, int maxEntries, Clock clock) {
        this.window = window;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    void remember(NewExceptionCase request, long workflowInstanceKey) {
        Instant now = clock.instant();
        if (entries.size() >= maxEntries) {
            entries.values().removeIf(entry -> expired(entry, now));
        }
        if (entries.size() < maxEntries) {
            entries.put(request.paymentId(), new Entry(request, workflowInstanceKey, now));
        }
    }

    Optional<Entry> find(PaymentId paymentId) {
        Entry entry = entries.get(paymentId);
        if (entry == null) {
            return Optional.empty();
        }
        if (expired(entry, clock.instant())) {
            entries.remove(paymentId, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    int size() {
        return entries.size();
    }

    private boolean expired(Entry entry, Instant now) {
        return !entry.openedAt().plus(window).isAfter(now);
    }
}
