package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Actor;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A person deciding cases: who they are and the queues (Keycloak groups) they work. */
public record Reviewer(Actor actor, Set<QueueName> queues) {

    public Reviewer {
        Objects.requireNonNull(actor, "actor");
        queues = Set.copyOf(queues);
    }

    public String subject() {
        return actor.subject();
    }

    /** A task routed to no queue is decided by nobody: it is a routing fault, not an open door. */
    public boolean memberOf(Optional<QueueName> queue) {
        return queue.isPresent() && queues.contains(queue.get());
    }
}
