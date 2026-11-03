package com.bank.bpo.exceptions.domain.casemanagement;

import java.util.Optional;

/** pay's answer to a recorded decision: the outcome of record (PaymentExceptionPort in the worker). */
public enum Outcome {
    SETTLE,
    RELEASE,
    HANDED_BACK;

    public static Optional<Outcome> of(String value) {
        for (Outcome outcome : values()) {
            if (outcome.name().equals(value)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }
}
