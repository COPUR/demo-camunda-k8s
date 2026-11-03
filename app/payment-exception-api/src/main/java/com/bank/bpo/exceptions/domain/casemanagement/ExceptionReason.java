package com.bank.bpo.exceptions.domain.casemanagement;

/**
 * Why a person is involved in a case. Each reason is one entry of the exception case catalog
 * ({@code catalog/exception-catalog.yaml}); a test checks that every code below exists there.
 */
public enum ExceptionReason {
    /** lib-await-external-status ended with Err_StatusTimeout: the scheme never gave a final status. */
    SCHEME_STATUS_TIMEOUT("EXC-001"),
    /** The scheme answered, but neither ACSC nor RJCT (Flow_StatusUnclear). */
    SCHEME_STATUS_UNCLEAR("EXC-002"),
    /** The operations reviewer escalated (ESCALATE). */
    OPERATIONS_ESCALATION("EXC-003"),
    /** Nobody in operations decided within the operations SLA (BoundaryEvent_OpsSla). */
    OPERATIONS_SLA_EXPIRED("EXC-004"),
    /** Nobody decided within the supervisor SLA either; the decision became EXPIRED (BoundaryEvent_SupervisorSla). */
    SUPERVISOR_SLA_EXPIRED("EXC-005");

    private final String catalogCode;

    ExceptionReason(String catalogCode) {
        this.catalogCode = catalogCode;
    }

    public String catalogCode() {
        return catalogCode;
    }
}
