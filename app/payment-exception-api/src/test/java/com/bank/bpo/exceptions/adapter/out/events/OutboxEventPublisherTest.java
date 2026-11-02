package com.bank.bpo.exceptions.adapter.out.events;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

    /**
     * The relay runs on its own thread, where the request's log context is gone. Each line about an event (the log
     * sink's, a retry's) carries the interaction id of the request the event came from, under the key the log
     * pattern reads (logging.pattern.correlation), and the next event's lines carry the next one's.
     */
    @Test
    void logsAboutEachEventUnderTheInteractionIdOfItsRequest() throws Exception {
        Logger outboxLog = (Logger) LoggerFactory.getLogger(OutboxEventPublisher.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        outboxLog.addAppender(lines);
        List<String> contextAtSend = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch two = new CountDownLatch(2);
        OutboxEventPublisher outbox = outbox(event -> {
            contextAtSend.add(String.valueOf(MDC.get("interactionId")));
            if (attempts.incrementAndGet() == 1) {
                throw new EventDeliveryException("broker down", null);
            }
            two.countDown();
        }, 10);
        InteractionId firstRequest = InteractionId.of("0b6f3c2a-8d1e-4f7a-9c5b-2e4d6f8a1c3e");
        InteractionId secondRequest = InteractionId.of("5d2e7a9c-1b3f-4e6d-8a0c-7f9e1d3b5a2c");
        try {
            outbox.start();
            outbox.publish(Events.raised("demo-tr-unres-627", firstRequest));
            outbox.publish(Events.raised("demo-tr-unres-628", secondRequest));

            assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
            outbox.close();
        } finally {
            outboxLog.detachAppender(lines);
        }

        assertThat(contextAtSend).containsExactly(firstRequest.value(), firstRequest.value(), secondRequest.value());
        assertThat(lines.list).singleElement().satisfies(retry -> {
            assertThat(retry.getLevel()).isEqualTo(Level.WARN);
            assertThat(retry.getMDCPropertyMap()).containsEntry("interactionId", firstRequest.value());
        });
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
