package com.bank.bpo.exceptions.application.port.out;

import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;

/**
 * Commands to the process that owns the case. Failures arrive as DomainException with WORKFLOW_UNAVAILABLE
 * (retryable), WORKFLOW_REJECTED or REVIEW_TASK_CHANGED, never as an engine exception.
 */
public interface CaseWorkflow {

    /** Starts the P2 process for the payment and returns the process instance key. */
    long open(NewExceptionCase request);

    /** Completes the open review task with the decision. */
    void submit(ExceptionDecisionSubmitted decision);
}
