package com.bank.bpo.exceptions.domain.shared;

/**
 * What kind of failure an {@link ErrorCode} is. The domain names the kind; each adapter maps it to its own protocol
 * (the REST adapter to an HTTP status, an event consumer to a dead-letter decision).
 */
public enum ErrorCategory {
    /** The request is malformed or a value object refused its input. */
    VALIDATION,
    /** The caller is not who it claims to be. */
    AUTHENTICATION,
    /** The caller may not do this, or not to this object. */
    AUTHORIZATION,
    /** The thing asked for does not exist. */
    NOT_FOUND,
    /** The request clashes with the current state; retrying the same request will not help. */
    CONFLICT,
    /** The caller's view is stale or the precondition is missing. */
    PRECONDITION,
    /** A business rule refuses an otherwise valid request. */
    BUSINESS_RULE,
    /** The caller sends too much or too often. */
    LIMIT,
    /** A dependency (the workflow engine) failed or answered outside its contract. */
    DEPENDENCY,
    /** A bug. */
    INTERNAL
}
