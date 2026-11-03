package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.Reviewer;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.util.Objects;

/** @param expectedToken the concurrency token (ETag) of the case the reviewer decided on; null when none was sent */
public record DecideCommand(PaymentId paymentId, Decision decision, Reviewer reviewer, String expectedToken, InteractionId interactionId,
                            IdempotencyKey idempotencyKey) {

    public DecideCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
