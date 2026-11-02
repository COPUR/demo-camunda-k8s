package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.DecideCommand;
import com.bank.bpo.exceptions.application.port.in.DecideExceptionCase;
import com.bank.bpo.exceptions.application.port.in.DecisionReceipt;
import com.bank.bpo.exceptions.application.port.in.DecisionResult;
import com.bank.bpo.exceptions.application.port.out.CaseWorkflow;
import com.bank.bpo.exceptions.application.port.out.DomainEventPublisher;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.time.Clock;

/**
 * Records a reviewer's decision: the aggregate checks the review rules, the workflow completes the task, the event
 * goes to the outbox. Two reviewers racing on one task are settled by the engine: the second completion is refused
 * and answered as REVIEW_TASK_CHANGED.
 */
public final class DecideExceptionCaseService implements DecideExceptionCase {

    private final ExceptionCaseRepository cases;
    private final CaseWorkflow workflow;
    private final DomainEventPublisher events;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public DecideExceptionCaseService(ExceptionCaseRepository cases, CaseWorkflow workflow, DomainEventPublisher events,
                                      IdempotencyStore idempotency, Clock clock) {
        this.cases = cases;
        this.workflow = workflow;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Override
    public DecisionResult decide(DecideCommand command) {
        IdempotentCommand.Ran<DecisionReceipt> ran = IdempotentCommand.run(idempotency,
            new IdempotencyStore.Scope("decide", command.reviewer().subject()), command.idempotencyKey(),
            Fingerprint.of("decide", command.paymentId().value(), command.decision().name(), String.valueOf(command.expectedToken())),
            DecisionReceipt.class, () -> submit(command));
        return new DecisionResult(ran.result(), ran.replayed());
    }

    private DecisionReceipt submit(DecideCommand command) {
        ExceptionCase exceptionCase = cases.findByPaymentId(command.paymentId())
            .orElseThrow(() -> new DomainException(ErrorCode.EXCEPTION_CASE_NOT_FOUND, "payment " + command.paymentId() + " has no exception case"));
        ExceptionDecisionSubmitted submitted = exceptionCase.decide(command.decision(), command.reviewer(), command.expectedToken(),
            command.interactionId(), clock.instant());
        workflow.submit(submitted);
        events.publish(submitted);
        return new DecisionReceipt(submitted.paymentId(), submitted.reviewTaskId(), submitted.level(), submitted.decision(), submitted.occurredAt());
    }
}
