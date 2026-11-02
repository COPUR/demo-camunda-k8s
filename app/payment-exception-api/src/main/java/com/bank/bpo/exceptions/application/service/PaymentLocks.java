package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Striped locks: commands for one payment run one at a time in this process, commands for different payments do not
 * wait for each other. This closes the check-then-act gap of "one case per payment" within a replica; across
 * replicas the process instance's business id is the fence (see the workflow adapter).
 */
final class PaymentLocks {

    private final ReentrantLock[] stripes;

    PaymentLocks(int stripes) {
        if (stripes < 1) {
            throw new IllegalArgumentException("at least one stripe");
        }
        this.stripes = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    ReentrantLock lockFor(PaymentId paymentId) {
        return stripes[Math.floorMod(paymentId.value().hashCode(), stripes.length)];
    }

    <T> T call(PaymentId paymentId, Supplier<T> action) {
        ReentrantLock lock = lockFor(paymentId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
