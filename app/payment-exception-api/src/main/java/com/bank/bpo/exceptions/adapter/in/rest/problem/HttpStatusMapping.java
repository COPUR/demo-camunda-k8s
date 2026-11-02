package com.bank.bpo.exceptions.adapter.in.rest.problem;

import com.bank.bpo.exceptions.domain.shared.ErrorCode;

/**
 * The HTTP status of every error code. Exhaustive on purpose: a new code does not compile until it has a status,
 * and CatalogConsistencyTest checks that the catalog documents the same one.
 */
public final class HttpStatusMapping {

    private HttpStatusMapping() {
    }

    public static int status(ErrorCode code) {
        return switch (code) {
            case INVALID_PAYMENT_ID, INVALID_JURISDICTION, UNSUPPORTED_RAIL, INVALID_DECISION, INVALID_IDEMPOTENCY_KEY,
                 IDEMPOTENCY_KEY_REQUIRED, INVALID_PAGE_REQUEST, MALFORMED_REQUEST, INVALID_QUEUE -> 400;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN, NOT_A_QUEUE_MEMBER -> 403;
            case EXCEPTION_CASE_NOT_FOUND, CATALOG_ENTRY_NOT_FOUND, RESOURCE_NOT_FOUND -> 404;
            case METHOD_NOT_ALLOWED -> 405;
            case NOT_ACCEPTABLE -> 406;
            case EXCEPTION_CASE_ALREADY_EXISTS, NO_OPEN_REVIEW, IDEMPOTENT_REQUEST_IN_PROGRESS, REVIEW_TASK_CLAIMED_BY_OTHER -> 409;
            case REVIEW_TASK_CHANGED -> 412;
            case PAYLOAD_TOO_LARGE -> 413;
            case UNSUPPORTED_MEDIA_TYPE -> 415;
            case IDEMPOTENCY_KEY_REUSED, DECISION_NOT_ALLOWED, JURISDICTION_NOT_SERVED -> 422;
            case PRECONDITION_REQUIRED -> 428;
            case RATE_LIMITED -> 429;
            case INTERNAL_ERROR -> 500;
            case WORKFLOW_REJECTED -> 502;
            case WORKFLOW_UNAVAILABLE -> 503;
        };
    }

    /** Seconds a client waits before retrying a retryable error, when the error itself does not say. */
    static int retryAfterSeconds(ErrorCode code) {
        return switch (code) {
            case IDEMPOTENT_REQUEST_IN_PROGRESS -> 1;
            case WORKFLOW_UNAVAILABLE -> 5;
            default -> 2;
        };
    }
}
