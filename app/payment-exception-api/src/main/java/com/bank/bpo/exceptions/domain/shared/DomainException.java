package com.bank.bpo.exceptions.domain.shared;

import java.util.Objects;

/**
 * A refusal with a catalogued {@link ErrorCode}. The message goes to the caller, so it names ids and rules only,
 * never a token, a credential or a stack trace.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public DomainException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
