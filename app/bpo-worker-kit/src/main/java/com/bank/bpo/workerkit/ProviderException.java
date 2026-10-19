package com.bank.bpo.workerkit;

/** A failed call to a domain service, classified the way 05 section 7 classifies errors. */
public final class ProviderException extends RuntimeException {

    public enum Kind {
        /** 422: a business outcome the model catches as a BPMN error. */
        BUSINESS_REJECTION,
        /** 5xx, 429, 409 CONCURRENT_UPDATE, timeout, connection loss: retry with the same key. */
        TRANSIENT,
        /** 409 IDEMPOTENCY_KEY_REUSED: a bug, never retried with a new key; an incident at once. */
        KEY_REUSED,
        /** Any other 4xx (401, 403, 404, 400): the contract or the wiring is wrong; an incident at once. */
        CONTRACT
    }

    private final Kind kind;
    private final String code;

    public ProviderException(Kind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    /** The provider's error code (for example INSUFFICIENT_FUNDS), or a local one for a network failure. */
    public String code() {
        return code;
    }
}
