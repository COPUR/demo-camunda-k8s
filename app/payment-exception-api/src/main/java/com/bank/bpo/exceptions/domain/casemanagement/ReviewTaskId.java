package com.bank.bpo.exceptions.domain.casemanagement;

/** One review task (a Zeebe user task key). A new task, such as the supervisor's after an escalation, has a new id. */
public record ReviewTaskId(long value) {

    public ReviewTaskId {
        if (value <= 0) {
            throw new IllegalArgumentException("a review task id is a positive engine key");
        }
    }

    /** The optimistic-concurrency token of a case while this task is open. */
    public String token() {
        return "rt-" + value;
    }
}
