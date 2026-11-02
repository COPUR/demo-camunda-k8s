package com.bank.bpo.exceptions.adapter.out.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxEventPublisherTest {

    final List<DomainEvent> sent = new CopyOnWriteArrayList<>();
    final List<Duration> waits = new CopyOnWriteArrayList<>();

    OutboxEventPublisher outbox(EventSink sink, int capacity) {
        return new OutboxEventPublisher(sink, capacity, Duration.ofMillis(100), Duration.ofMillis(300), waits::add, () -> 1.0);
    }

    @Test
    void deliversInTheOrderTheyWerePublished() throws Exception {
        CountDownLatch three = new CountDownLatch(3);
        OutboxEventPublisher outbox = outbox(event -> {
            sent.add(event);
            three.countDown();
        }, 10);
        outbox.start();
        outbox.start();

        outbox.publish(Events.raised("demo-tr-unres-621"));
        outbox.publish(Events.raised("demo-tr-unres-622"));
        outbox.publish(Events.raised("demo-tr-unres-623"));

        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sent).extracting(event -> event.paymentId().value())
            .containsExactly("demo-tr-unres-621", "demo-tr-unres-622", "demo-tr-unres-623");
        outbox.close();
        assertThat(outbox.delivered()).isEqualTo(3);
        assertThat(outbox.dropped()).isZero();
    }

    @Test
    void waitsOutABrokerOutageWithCappedExponentialBackoff() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        OutboxEventPublisher outbox = outbox(event -> {
            if (attempts.incrementAndGet() < 5) {
                throw new EventDeliveryException("broker down", null);
            }
            delivered.countDown();
        }, 10);
        outbox.start();

        outbox.publish(Events.raised("demo-tr-unres-624"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(waits).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofMillis(300));
        outbox.close();
        assertThat(outbox.delivered()).isEqualTo(1);
    }

    @Test
    void aFullOutboxDropsTheNewestEventAndCountsIt() throws Exception {
        OutboxEventPublisher outbox = outbox(sent::add, 1);

        outbox.publish(Events.raised("demo-tr-unres-625"));
        outbox.publish(Events.raised("demo-tr-unres-626"));

        assertThat(outbox.pending()).isEqualTo(1);
        assertThat(outbox.dropped()).isEqualTo(1);
        outbox.close();
        assertThat(outbox.dropped()).isEqualTo(2);
    }

    @Test
    void closingDrainsWhatIsQueuedAndGivesUpOnAnEventThatCannotBeDelivered() throws Exception {
        CountDownLatch failing = new CountDownLatch(1);
        OutboxEventPublisher outbox = new OutboxEventPublisher(event -> {
            failing.countDown();
            throw new EventDeliveryException("broker down", null);
        }, 10, Duration.ofMillis(10), Duration.ofMillis(10), duration -> Thread.sleep(duration), () -> 1.0);
        outbox.start();
        outbox.publish(Events.raised("demo-tr-unres-627"));
        assertThat(failing.await(5, TimeUnit.SECONDS)).isTrue();

        outbox.close();

        assertThat(outbox.delivered()).isZero();
        assertThat(outbox.dropped()).isEqualTo(1);
    }

    @Test
    void aRelayStuckInALongBackoffIsInterruptedOnClose() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        OutboxEventPublisher outbox = new OutboxEventPublisher(event -> {
            throw new EventDeliveryException("broker down", null);
        }, 10, Duration.ofMillis(10), Duration.ofMillis(10), duration -> {
            sleeping.countDown();
            Thread.sleep(60_000);
        }, () -> 1.0) ;
        outbox.start();
        outbox.publish(Events.raised("demo-tr-unres-628"));
        outbox.publish(Events.raised("demo-tr-unres-629"));
        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        outbox.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(outbox.dropped()).isEqualTo(2);
    }

    @Test
    void theSystemClockOutboxUsesRealWaits() throws Exception {
        OutboxEventPublisher outbox = OutboxEventPublisher.withSystemClock(sent::add, 1, Duration.ofMillis(1), Duration.ofMillis(1));
        outbox.close();
        assertThat(outbox.pending()).isZero();
    }
}
