package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A payment became an exception case and its process instance runs. */
public record ExceptionCaseRaised(UUID eventId, PaymentId paymentId, Jurisdiction jurisdiction, Rail rail, long workflowInstanceKey,
                                  Actor actor, InteractionId interactionId, Instant occurredAt) implements DomainEvent {

    public ExceptionCaseRaised {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
