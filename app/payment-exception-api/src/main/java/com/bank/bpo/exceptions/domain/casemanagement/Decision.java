package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;

/**
 * What the scheme record shows, as decided in a review (the values of the Tasklist forms
 * {@code pay-exception-ops-decision} and {@code pay-exception-supervisor-decision}). pay checks every decision
 * against the gateway's record; the outcome of record is pay's ({@link Outcome}).
 */
public enum Decision {
    SETTLED(true),
    REJECTED(true),
    ESCALATE(true),
    /** Set by the supervisor SLA timer ({@code Event_DecisionExpired}); never by a person. */
    EXPIRED(false);

    private final boolean byPerson;

    Decision(boolean byPerson) {
        this.byPerson = byPerson;
    }

    public boolean byPerson() {
        return byPerson;
    }

    public static Decision of(String value) {
        if (value != null) {
            for (Decision decision : values()) {
                if (decision.name().equals(value)) {
                    return decision;
                }
            }
        }
        throw new DomainException(ErrorCode.INVALID_DECISION, "decision must be SETTLED, REJECTED or ESCALATE");
    }
}
