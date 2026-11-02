package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.RaiseCommand;
import com.bank.bpo.exceptions.application.port.in.RaiseExceptionCase;
import com.bank.bpo.exceptions.application.port.in.RaiseResult;
import com.bank.bpo.exceptions.application.port.out.CaseWorkflow;
import com.bank.bpo.exceptions.application.port.out.DomainEventPublisher;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.JurisdictionPolicy;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.time.Clock;

/**
 * Opens a case: one per payment, once per idempotency key. The process instance is written first and is the record;
 * the event goes to the outbox after it, so an event never announces a case that does not exist.
 */
public final class RaiseExceptionCaseService implements RaiseExceptionCase {

    private final JurisdictionPolicy jurisdictions;
    private final ExceptionCaseRepository cases;
    private final CaseWorkflow workflow;
    private final DomainEventPublisher events;
    private final IdempotencyStore idempotency;
    private final Clock clock;
    private final PaymentLocks locks = new PaymentLocks(64);

    public RaiseExceptionCaseService(JurisdictionPolicy jurisdictions, ExceptionCaseRepository cases, CaseWorkflow workflow,
                                     DomainEventPublisher events, IdempotencyStore idempotency, Clock clock) {
        this.jurisdictions = jurisdictions;
        this.cases = cases;
        this.workflow = workflow;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Override
    public RaiseResult raise(RaiseCommand command) {
        NewExceptionCase request = command.request();
        jurisdictions.requireServed(request.jurisdiction());
        IdempotentCommand.Ran<ExceptionCase> ran = IdempotentCommand.run(idempotency,
            new IdempotencyStore.Scope("raise", command.actor().subject()), command.idempotencyKey(),
            Fingerprint.of("raise", request.paymentId().value(), request.jurisdiction().code(), request.rail().name()), ExceptionCase.class,
            () -> locks.call(request.paymentId(), () -> open(command)));
        return new RaiseResult(ran.result(), ran.replayed());
    }

    private ExceptionCase open(RaiseCommand command) {
        NewExceptionCase request = command.request();
        if (cases.findByPaymentId(request.paymentId()).isPresent()) {
            throw new DomainException(ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS, "payment " + request.paymentId() + " already has an exception case");
        }
        long workflowInstanceKey = workflow.open(request);
        ExceptionCase.Opened opened = ExceptionCase.open(request, workflowInstanceKey, command.actor(), command.interactionId(), clock.instant());
        events.publish(opened.event());
        return opened.exceptionCase();
    }
}
