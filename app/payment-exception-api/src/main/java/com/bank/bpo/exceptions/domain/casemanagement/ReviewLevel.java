package com.bank.bpo.exceptions.domain.casemanagement;

import java.util.List;

/** Who looks at a case: payments operations first, their supervisor after an escalation or an expired SLA. */
public enum ReviewLevel {
    OPERATIONS(List.of(Decision.SETTLED, Decision.REJECTED, Decision.ESCALATE)),
    SUPERVISOR(List.of(Decision.SETTLED, Decision.REJECTED));

    private final List<Decision> allowedDecisions;

    ReviewLevel(List<Decision> allowedDecisions) {
        this.allowedDecisions = allowedDecisions;
    }

    public List<Decision> allowedDecisions() {
        return allowedDecisions;
    }

    public boolean allows(Decision decision) {
        return allowedDecisions.contains(decision);
    }
}
