package com.bank.bpo.exceptions.adapter.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void waitsTheWholeSecondsUntilTheNextToken() {
        AtomicLong nanos = new AtomicLong();
        TokenBucket bucket = new TokenBucket(1, 0.25, nanos::get);
        assertThat(bucket.tryAcquire()).isZero();
        assertThat(bucket.tryAcquire()).isEqualTo(4);
        nanos.addAndGet(2_000_000_000L);
        assertThat(bucket.tryAcquire()).isEqualTo(2);
        nanos.addAndGet(2_000_000_000L);
        assertThat(bucket.tryAcquire()).isZero();
    }

    @Test
    void neverHoldsMoreThanItsCapacity() {
        AtomicLong nanos = new AtomicLong();
        TokenBucket bucket = new TokenBucket(2, 100, nanos::get);
        nanos.addAndGet(60_000_000_000L);
        assertThat(bucket.tryAcquire()).isZero();
        assertThat(bucket.tryAcquire()).isZero();
        assertThat(bucket.tryAcquire()).isEqualTo(1);
    }

    @Test
    void refusesABucketThatNeverGrants() {
        assertThatThrownBy(() -> new TokenBucket(0, 1, System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
    }
}
