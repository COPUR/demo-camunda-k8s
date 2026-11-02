package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import java.util.Objects;

public record RaiseCommand(NewExceptionCase request, Actor actor, InteractionId interactionId, IdempotencyKey idempotencyKey) {

    public RaiseCommand {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
