package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.EventId;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root of the case-management sub-domain: one instant payment whose scheme status stayed unknown and that
 * people now look at. The process instance in Camunda is the system of record; this is the case as the domain sees
 * it, read through the workflow adapter, and the place where the review rules live.
 *
 * @param workflowInstanceKey the process instance; shown for support (Operate), never used as the case's identity
 */
public record ExceptionCase(PaymentId paymentId, Jurisdiction jurisdiction, Rail rail, CaseState state,
                            Optional<SchemeStatus> schemeStatus, Optional<ExceptionReason> reason, Optional<ReviewTask> openReview,
                            Optional<Decision> decision, Optional<Outcome> outcome, boolean incident, Instant raisedAt,
                            long workflowInstanceKey) {

    public ExceptionCase {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(schemeStatus, "schemeStatus");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(openReview, "openReview");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(raisedAt, "raisedAt");
        if ((state == CaseState.IN_REVIEW) != openReview.isPresent()) {
            throw new IllegalArgumentException("a case is IN_REVIEW exactly when one review task is open");
        }
        if (workflowInstanceKey <= 0) {
            throw new IllegalArgumentException("a case runs in a process instance");
        }
    }

    /** The case and its event, once the workflow has started the process instance. */
    public record Opened(ExceptionCase exceptionCase, ExceptionCaseRaised event) {
    }

    public static Opened open(NewExceptionCase request, long workflowInstanceKey, Actor actor, InteractionId interactionId, Instant now) {
        ExceptionCase opened = new ExceptionCase(request.paymentId(), request.jurisdiction(), request.rail(), CaseState.AWAITING_SCHEME_STATUS,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, now, workflowInstanceKey);
        ExceptionCaseRaised event = new ExceptionCaseRaised(EventId.forFact("ExceptionCaseRaised", request.paymentId().value()),
            request.paymentId(), request.jurisdiction(), request.rail(), workflowInstanceKey, actor, interactionId, now);
        return new Opened(opened, event);
    }

    /**
     * Changes whenever what a reviewer decides on changes: a new review task (escalation, expired SLA) or a new
     * state. A decision must name the token it was taken on, so nobody decides on a view that is gone.
     */
    public String concurrencyToken() {
        return openReview.map(task -> task.id().token()).orElseGet(() -> state.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The review rules: there must be an open review, the reviewer decided on the current one, the decision is one
     * a person may take at this level, the reviewer works the queue it is routed to, and nobody else claimed it.
     */
    public ExceptionDecisionSubmitted decide(Decision decision, Reviewer reviewer, String expectedToken, InteractionId interactionId,
                                             Instant now) {
        ReviewTask task = openReview.orElseThrow(() -> new DomainException(ErrorCode.NO_OPEN_REVIEW,
            "the case of " + paymentId + " is " + state + ", not waiting for a decision"));
        if (expectedToken == null) {
            throw new DomainException(ErrorCode.PRECONDITION_REQUIRED, "send If-Match with the ETag of the case you decided on");
        }
        if (!task.id().token().equals(expectedToken)) {
            throw new DomainException(ErrorCode.REVIEW_TASK_CHANGED, "the review of " + paymentId + " changed; read the case again");
        }
        if (!decision.byPerson() || !task.level().allows(decision)) {
            throw new DomainException(ErrorCode.DECISION_NOT_ALLOWED, decision + " is not a decision of the " + task.level() + " review");
        }
        if (!reviewer.memberOf(task.queue())) {
            throw new DomainException(ErrorCode.NOT_A_QUEUE_MEMBER, "the review of " + paymentId + " is routed to a queue the reviewer does not work");
        }
        if (task.assignee().isPresent() && !task.assignee().get().equals(reviewer.subject())) {
            throw new DomainException(ErrorCode.REVIEW_TASK_CLAIMED_BY_OTHER, "the review of " + paymentId + " is claimed by another reviewer");
        }
        return new ExceptionDecisionSubmitted(
            EventId.forFact("ExceptionDecisionSubmitted", paymentId.value(), Long.toString(task.id().value())), paymentId,
            workflowInstanceKey, task.id(), task.level(), task.queue(), decision, reviewer.actor(), interactionId, now);
    }
}
