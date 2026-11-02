package com.bank.bpo.exceptions.domain.casemanagement;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The open review of a case: its level, the queue it is routed to, when its SLA ends, and who claimed it in
 * Tasklist (if anyone). The case holds no payment detail beyond its id: the reviewer reads the scheme record in the
 * payments operations view.
 */
public record ReviewTask(ReviewTaskId id, ReviewLevel level, Optional<QueueName> queue, Optional<Instant> dueAt,
                         Optional<String> assignee) {

    public ReviewTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(assignee, "assignee");
    }

    public List<Decision> allowedDecisions() {
        return level.allowedDecisions();
    }
}
