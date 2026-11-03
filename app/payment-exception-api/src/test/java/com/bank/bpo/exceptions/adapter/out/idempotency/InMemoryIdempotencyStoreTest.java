package com.bank.bpo.exceptions.adapter.out.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore.Claim;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    static final Scope RAISE = new Scope("raise", "svc-pay");
    static final IdempotencyKey KEY = IdempotencyKey.of("key-0001");

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void theFirstClaimRunsLaterOnesReplayOrAreRefused() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(1), 10, Clock.systemUTC());

        assertThat(store.claim(RAISE, KEY, "fp-1")).isInstanceOf(Claim.Acquired.class);
        assertThat(store.claim(RAISE, KEY, "fp-1")).isInstanceOf(Claim.InProgress.class);
        store.complete(RAISE, KEY, "result");
        assertThat(store.claim(RAISE, KEY, "fp-1")).isEqualTo(new Claim.Replay("result"));
        assertThat(store.claim(RAISE, KEY, "fp-2")).isInstanceOf(Claim.Mismatch.class);
    }

    @Test
    void keysAreScopedByOperationAndCaller() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(1), 10, Clock.systemUTC());
        store.claim(RAISE, KEY, "fp-1");
        store.complete(RAISE, KEY, "result");

        assertThat(store.claim(new Scope("raise", "svc-other"), KEY, "fp-1")).isInstanceOf(Claim.Acquired.class);
        assertThat(store.claim(new Scope("decide", "svc-pay"), KEY, "fp-1")).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    void aReleasedClaimCanBeTakenAgain() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(1), 10, Clock.systemUTC());
        store.claim(RAISE, KEY, "fp-1");
        store.release(RAISE, KEY);

        assertThat(store.claim(RAISE, KEY, "fp-1")).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    void recordsExpireAfterTheirTimeToLive() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofMinutes(10), 10, clock);
        store.claim(RAISE, KEY, "fp-1");
        store.complete(RAISE, KEY, "result");

        clock.now.set(clock.now.get().plus(Duration.ofMinutes(11)));
        assertThat(store.claim(RAISE, KEY, "fp-2")).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    void theStoreIsBoundedSoNoCallerCanExhaustMemory() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofMinutes(10), 2, clock);
        store.claim(RAISE, IdempotencyKey.of("key-0001"), "fp");
        clock.now.set(clock.now.get().plusSeconds(1));
        store.claim(RAISE, IdempotencyKey.of("key-0002"), "fp");

        // Every slot holds a running request: nothing may be dropped, the caller comes back later.
        assertThat(store.claim(RAISE, IdempotencyKey.of("key-0003"), "fp")).isInstanceOf(Claim.Full.class);
        assertThat(store.size()).isEqualTo(2);

        // When space is needed the oldest finished record goes (key-0001); running ones never do.
        store.complete(RAISE, IdempotencyKey.of("key-0002"), "second");
        store.complete(RAISE, IdempotencyKey.of("key-0001"), "first");
        assertThat(store.claim(RAISE, IdempotencyKey.of("key-0003"), "fp")).isInstanceOf(Claim.Acquired.class);
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.claim(RAISE, IdempotencyKey.of("key-0002"), "fp")).isEqualTo(new Claim.Replay("second"));

        clock.now.set(clock.now.get().plus(Duration.ofMinutes(11)));
        assertThat(store.claim(RAISE, IdempotencyKey.of("key-0004"), "fp")).isInstanceOf(Claim.Acquired.class);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void completingOrReleasingAnUnknownClaimIsHarmless() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(1), 10, Clock.systemUTC());
        store.complete(RAISE, KEY, "result");
        store.release(RAISE, KEY);
        assertThat(store.size()).isZero();
        assertThatThrownBy(() -> new InMemoryIdempotencyStore(Duration.ZERO, 10, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryIdempotencyStore(Duration.ofHours(1), 0, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
}
