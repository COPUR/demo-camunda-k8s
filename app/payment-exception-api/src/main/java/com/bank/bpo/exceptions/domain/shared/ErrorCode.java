package com.bank.bpo.exceptions.domain.shared;

import java.util.Arrays;
import java.util.Optional;

/**
 * The exception repository in code: every error the API can answer with. The documented copy is
 * {@code catalog/exception-catalog.yaml}; a test keeps the two identical, so a code cannot be added, renamed or
 * re-categorised in one place only. Codes are stable: a retired code is never reused.
 */
public enum ErrorCode {
    // 1xxx: validation, the request or a value object is wrong.
    INVALID_PAYMENT_ID("PEX-1001", "Payment id is not a synthetic demo-tr id", ErrorCategory.VALIDATION, false),
    INVALID_JURISDICTION("PEX-1002", "Jurisdiction is not an ISO 3166-1 alpha-2 code", ErrorCategory.VALIDATION, false),
    UNSUPPORTED_RAIL("PEX-1003", "Payment rail is not supported", ErrorCategory.VALIDATION, false),
    INVALID_DECISION("PEX-1004", "Decision is missing or unknown", ErrorCategory.VALIDATION, false),
    INVALID_IDEMPOTENCY_KEY("PEX-1005", "Idempotency-Key is malformed", ErrorCategory.VALIDATION, false),
    IDEMPOTENCY_KEY_REQUIRED("PEX-1006", "Idempotency-Key header is required", ErrorCategory.VALIDATION, false),
    INVALID_PAGE_REQUEST("PEX-1007", "Page size or page token is invalid", ErrorCategory.VALIDATION, false),
    MALFORMED_REQUEST("PEX-1008", "Request body cannot be read", ErrorCategory.VALIDATION, false),
    INVALID_QUEUE("PEX-1009", "Queue name is invalid", ErrorCategory.VALIDATION, false),
    // 2xxx: authentication and authorization.
    UNAUTHENTICATED("PEX-2001", "A valid bearer token is required", ErrorCategory.AUTHENTICATION, false),
    FORBIDDEN("PEX-2002", "The token does not grant this operation", ErrorCategory.AUTHORIZATION, false),
    NOT_A_QUEUE_MEMBER("PEX-2003", "Reviewer is not a member of the routed queue", ErrorCategory.AUTHORIZATION, false),
    // 3xxx: state, concurrency and idempotency.
    EXCEPTION_CASE_NOT_FOUND("PEX-3001", "Exception case not found", ErrorCategory.NOT_FOUND, false),
    EXCEPTION_CASE_ALREADY_EXISTS("PEX-3002", "Payment already has an exception case", ErrorCategory.CONFLICT, false),
    NO_OPEN_REVIEW("PEX-3003", "Exception case is not waiting for a decision", ErrorCategory.CONFLICT, false),
    REVIEW_TASK_CHANGED("PEX-3004", "Review task changed since it was read", ErrorCategory.PRECONDITION, false),
    PRECONDITION_REQUIRED("PEX-3005", "If-Match header is required", ErrorCategory.PRECONDITION, false),
    IDEMPOTENCY_KEY_REUSED("PEX-3006", "Idempotency-Key was used for a different request", ErrorCategory.CONFLICT, false),
    IDEMPOTENT_REQUEST_IN_PROGRESS("PEX-3007", "A request with this Idempotency-Key is still running", ErrorCategory.CONFLICT, true),
    REVIEW_TASK_CLAIMED_BY_OTHER("PEX-3008", "Review task is claimed by another reviewer", ErrorCategory.CONFLICT, false),
    CATALOG_ENTRY_NOT_FOUND("PEX-3009", "Catalog entry not found", ErrorCategory.NOT_FOUND, false),
    // 4xxx: business rules.
    DECISION_NOT_ALLOWED("PEX-4001", "Decision is not allowed at this review level", ErrorCategory.BUSINESS_RULE, false),
    JURISDICTION_NOT_SERVED("PEX-4002", "This deployment does not serve the jurisdiction", ErrorCategory.BUSINESS_RULE, false),
    // 5xxx: protocol and limits.
    PAYLOAD_TOO_LARGE("PEX-5001", "Request body is too large", ErrorCategory.LIMIT, false),
    UNSUPPORTED_MEDIA_TYPE("PEX-5002", "Content-Type is not supported", ErrorCategory.VALIDATION, false),
    NOT_ACCEPTABLE("PEX-5003", "No representation matches the Accept header", ErrorCategory.VALIDATION, false),
    RATE_LIMITED("PEX-5004", "Too many requests", ErrorCategory.LIMIT, true),
    METHOD_NOT_ALLOWED("PEX-5005", "Method is not allowed on this resource", ErrorCategory.VALIDATION, false),
    RESOURCE_NOT_FOUND("PEX-5006", "No such resource", ErrorCategory.NOT_FOUND, false),
    // 9xxx: dependencies and bugs.
    WORKFLOW_UNAVAILABLE("PEX-9001", "Workflow engine is unavailable", ErrorCategory.DEPENDENCY, true),
    WORKFLOW_REJECTED("PEX-9002", "Workflow engine refused the command", ErrorCategory.DEPENDENCY, false),
    INTERNAL_ERROR("PEX-9999", "Internal error", ErrorCategory.INTERNAL, false);

    private final String code;
    private final String title;
    private final ErrorCategory category;
    private final boolean retryable;

    ErrorCode(String code, String title, ErrorCategory category, boolean retryable) {
        this.code = code;
        this.title = title;
        this.category = category;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public ErrorCategory category() {
        return category;
    }

    /** True when the same request may succeed later; the caller then retries with backoff and the same key. */
    public boolean retryable() {
        return retryable;
    }

    public static Optional<ErrorCode> fromCode(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }
}
