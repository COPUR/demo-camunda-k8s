package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A reviewer decided an open review task. It is also the command the workflow executes (complete the task); pay
 * records the decision and answers with the outcome of record afterwards.
 */
public record ExceptionDecisionSubmitted(UUID eventId, PaymentId paymentId, long workflowInstanceKey, ReviewTaskId reviewTaskId,
                                         ReviewLevel level, Optional<QueueName> queue, Decision decision, Actor actor,
                                         InteractionId interactionId, Instant occurredAt) implements DomainEvent {

    public ExceptionDecisionSubmitted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(reviewTaskId, "reviewTaskId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
