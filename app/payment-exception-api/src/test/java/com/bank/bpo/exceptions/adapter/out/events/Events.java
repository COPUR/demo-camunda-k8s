package com.bank.bpo.exceptions.adapter.out.events;

import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCaseRaised;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.EventId;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Instant;
import java.util.Optional;

/** Synthetic events as the use cases raise them. */
final class Events {

    static final PaymentId PAYMENT = PaymentId.of("demo-tr-unres-601");
    static final InteractionId INTERACTION = InteractionId.of("7f9c1d6e-2f0a-4c55-9a3b-1e2d3c4b5a69");

    private Events() {
    }

    static ExceptionCaseRaised raised(String paymentId) {
        PaymentId payment = PaymentId.of(paymentId);
        return new ExceptionCaseRaised(EventId.forFact("ExceptionCaseRaised", paymentId), payment, Jurisdiction.of("TR"), Rail.FAST,
            2251799813685300L, new Actor("svc-bpo-worker-payments"), INTERACTION, Instant.parse("2026-11-02T10:00:00.123Z"));
    }

    static ExceptionDecisionSubmitted decided(Optional<QueueName> queue) {
        return new ExceptionDecisionSubmitted(EventId.forFact("ExceptionDecisionSubmitted", PAYMENT.value(), "2251799813685400"), PAYMENT,
            2251799813685300L, new ReviewTaskId(2251799813685400L), ReviewLevel.SUPERVISOR, queue, Decision.REJECTED, new Actor("sup.mehmet"),
            INTERACTION, Instant.parse("2026-11-02T10:40:00Z"));
    }
}
