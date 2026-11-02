package com.bank.bpo.exceptions.domain.casemanagement;

import java.util.Optional;

/**
 * The status the TR FAST scheme reports for the payment, as ISO 20022 ExternalPaymentTransactionStatus codes.
 * It belongs to the scheme-status sub-domain (psg-tr); this context only reads it.
 */
public enum SchemeStatus {
    ACSC("accepted, settlement completed"),
    RJCT("rejected"),
    PDNG("pending");

    private final String description;

    SchemeStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Empty while the status is unknown (no answer yet, or the inquiry timed out) or for a code this context does not use. */
    public static Optional<SchemeStatus> of(String value) {
        for (SchemeStatus status : values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
