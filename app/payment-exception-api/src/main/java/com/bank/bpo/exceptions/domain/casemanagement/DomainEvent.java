package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Instant;
import java.util.UUID;

/** A fact of the case-management sub-domain, published as an Avro event keyed by the payment id. */
public sealed interface DomainEvent permits ExceptionCaseRaised, ExceptionDecisionSubmitted {

    /** Derived from the fact (see EventId), so a retried publication is recognisable as the same event. */
    UUID eventId();

    PaymentId paymentId();

    Actor actor();

    InteractionId interactionId();

    Instant occurredAt();
}
