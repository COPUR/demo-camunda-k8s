package com.bank.bpo.payments.port;

/** acc: the hold pay placed for an instant payment. Capturing it is the pivot of P2 (05 section 7). */
public interface HoldPort {

    /** Captures the hold and returns the journal entry; a replay with the same key returns the same entry. */
    String capture(String paymentId, String idempotencyKey);

    void release(String paymentId, String idempotencyKey);
}
