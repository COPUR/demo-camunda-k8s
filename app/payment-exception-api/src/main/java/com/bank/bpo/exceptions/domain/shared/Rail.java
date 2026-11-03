package com.bank.bpo.exceptions.domain.shared;

/** The payment rail. The exception process routes by rail; this context handles TR FAST instant payments only. */
public enum Rail {
    FAST;

    public static Rail of(String value) {
        if (value != null) {
            for (Rail rail : values()) {
                if (rail.name().equals(value)) {
                    return rail;
                }
            }
        }
        throw new DomainException(ErrorCode.UNSUPPORTED_RAIL, "rail must be FAST");
    }
}
