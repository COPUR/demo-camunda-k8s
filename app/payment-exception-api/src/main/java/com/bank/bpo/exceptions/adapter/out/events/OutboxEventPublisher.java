package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.application.port.out.DomainEventPublisher;
import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Decouples the request from the broker: {@link #publish} only queues, so a slow or absent broker never fails or
 * slows a command whose fact the engine already recorded. One relay thread delivers in order and retries with
 * capped exponential backoff and full jitter, so a broker outage is waited out instead of hammered. Delivery is at
 * least once (consumers de-duplicate by the deterministic event id).
 *
 * <p>The queue is in memory and bounded: events queued when the process dies are lost, and when the queue is full
 * the newest event is dropped and counted. The engine stays the record of every fact; a durable outbox (a table, or
 * the engine's exporter) is the step after the demo.
 */
public final class OutboxEventPublisher implements DomainEventPublisher, AutoCloseable {

    /** Waits between attempts; a test passes one that does not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);
    private static final Duration DRAIN_ON_CLOSE = Duration.ofSeconds(10);
    /** The log context key logging.pattern.correlation reads; the HTTP edge sets it for the request's thread. */
    private static final String LOG_CONTEXT_KEY = "interactionId";

    private final BlockingQueue<DomainEvent> queue;
    private final EventSink sink;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private Thread relay;

    public OutboxEventPublisher(EventSink sink, int capacity, Duration initialBackoff, Duration maxBackoff, Sleeper sleeper, DoubleSupplier jitter) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink = sink;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    public static OutboxEventPublisher withSystemClock(EventSink sink, int capacity, Duration initialBackoff, Duration maxBackoff) {
        return new OutboxEventPublisher(sink, capacity, initialBackoff, maxBackoff, duration -> Thread.sleep(duration),
            () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    @Override
    public void publish(DomainEvent event) {
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            log.error("outbox full: event {} {} for payment {} dropped (the engine still holds the fact)",
                AvroEventCodec.eventType(event), event.eventId(), event.paymentId());
        }
    }

    public synchronized void start() {
        if (relay == null) {
            running = true;
            relay = Thread.ofPlatform().name("payment-exception-outbox").daemon(true).start(this::relay);
        }
    }

    /** Stops taking new work, delivers what is queued for up to ten seconds, then gives up on the rest. */
    @Override
    public synchronized void close() throws InterruptedException {
        running = false;
        if (relay != null) {
            relay.join(DRAIN_ON_CLOSE.toMillis());
            if (relay.isAlive()) {
                relay.interrupt();
                relay.join(1000);
            }
            relay = null;
        }
        int left = queue.size();
        if (left > 0) {
            dropped.addAndGet(left);
            queue.clear();
            log.error("outbox closed with {} undelivered events", left);
        }
    }

    public long delivered() {
        return delivered.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public int pending() {
        return queue.size();
    }

    private void relay() {
        try {
            while (running || !queue.isEmpty()) {
                DomainEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && !deliver(event)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Delivers one event, retrying until it is taken; false when the outbox was closed before it could be. Its lines
     * are logged under the interaction id of the request the event came from, as that request's own lines are.
     */
    boolean deliver(DomainEvent event) throws InterruptedException {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(LOG_CONTEXT_KEY, event.interactionId().value())) {
            return attempt(event);
        }
    }

    private boolean attempt(DomainEvent event) throws InterruptedException {
        Duration backoff = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                sink.send(event);
                delivered.incrementAndGet();
                return true;
            } catch (RuntimeException e) {
                if (!running) {
                    dropped.incrementAndGet();
                    log.error("outbox closing: event {} for payment {} not delivered after {} attempts", event.eventId(), event.paymentId(), attempt, e);
                    return false;
                }
                Duration wait = Duration.ofMillis((long) (backoff.toMillis() * jitter.getAsDouble()));
                log.warn("event {} for payment {} not delivered (attempt {}), next attempt in {} ms: {}", event.eventId(), event.paymentId(),
                    attempt, wait.toMillis(), e.getMessage());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    dropped.incrementAndGet();
                    throw interrupted;
                }
                backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
            }
        }
    }
}
