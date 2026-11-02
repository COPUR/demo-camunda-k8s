package com.bank.bpo.exceptions.domain.casemanagement;

/** Where an exception case stands, in the case-management language rather than the engine's element ids. */
public enum CaseState {
    /** The scheme status inquiry (lib-await-external-status) is still running. */
    AWAITING_SCHEME_STATUS(false),
    /** A person must decide: one review task is open. */
    IN_REVIEW(false),
    /** The status is known or the decision is recorded; the hold and the payment are being settled or released. */
    RESOLVING(false),
    SETTLED(true),
    REJECTED(true),
    /** pay took the payment back (an expired review, or pay disagreed with the decision); the hold stays. */
    HANDED_BACK(true),
    /** The process instance was cancelled (in Operate). */
    CANCELED(true);

    private final boolean terminal;

    CaseState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
