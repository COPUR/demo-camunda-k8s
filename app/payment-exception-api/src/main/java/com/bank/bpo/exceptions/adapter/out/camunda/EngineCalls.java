package com.bank.bpo.exceptions.adapter.out.camunda;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import io.camunda.client.api.command.ClientHttpException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * One engine call and what its failure means to the API. The adapter adds no retry of its own: the engine client
 * already re-sends once on 429 and 503 (its HTTP client's default), and the API's caller retries with the same
 * Idempotency-Key when the answer says it may, so retries do not multiply. The engine's own message is logged by the
 * exception handler, never sent to the caller.
 */
final class EngineCalls {

    private EngineCalls() {
    }

    /**
     * Runs the call; an HTTP status listed in {@code meanings} is a domain answer (a review task that is gone is a
     * changed view), any other 4xx is an engine that refused a request it should have taken (502), and 429, 5xx,
     * timeouts and connection failures are an engine that is not there now (503, retryable).
     */
    static <T> T call(String what, Supplier<T> call, Map<Integer, ErrorCode> meanings) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw translate(what, e, meanings);
        }
    }

    static DomainException translate(String what, Throwable failure, Map<Integer, ErrorCode> meanings) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof DomainException domain) {
            return domain;
        }
        if (cause instanceof ClientHttpException http) {
            int status = http.code();
            ErrorCode meaning = meanings.get(status);
            if (meaning != null) {
                return new DomainException(meaning, what + ": the engine answered " + status, cause);
            }
            if (status == 429 || status >= 500) {
                return new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, what + ": the engine answered " + status, cause);
            }
            return new DomainException(ErrorCode.WORKFLOW_REJECTED, what + ": the engine refused the request (" + status + ")", cause);
        }
        return new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, what + ": the engine did not answer", cause);
    }
}
