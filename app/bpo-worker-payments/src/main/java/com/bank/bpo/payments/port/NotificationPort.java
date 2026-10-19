package com.bank.bpo.payments.port;

/** ntf: a transactional notification about a payment. ntf finds the customer and the channel from the payment. */
public interface NotificationPort {

    String notify(String subjectRef, String purpose, String idempotencyKey);
}
