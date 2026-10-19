package com.bank.bpo.payments.port;

/** pay: the payment while it is EXCEPTION_OWNED_BY_BPO. The outcome of record is pay's. */
public interface PaymentExceptionPort {

    enum Outcome { SETTLE, RELEASE, HANDED_BACK }

    /** Posts the operations decision (SETTLED, REJECTED or EXPIRED); pay checks it against the gateway's record. */
    Outcome record(String paymentId, String decision, String idempotencyKey);

    void markSettled(String paymentId, String journalRef, String idempotencyKey);

    void markRejected(String paymentId, String idempotencyKey);
}
