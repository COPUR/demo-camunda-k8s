package com.bank.bpo.workerkit;

import io.camunda.client.exception.CamundaError;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Turns a provider failure into what the engine should do with the job (05 section 7, point 4):
 * a business rejection becomes a BPMN error the model catches, a transient failure fails the job so it
 * is retried with the same idempotency key after an exponential backoff, and a bug or a wrong contract
 * raises an incident at once.
 */
public final class JobErrors {

    private JobErrors() {
    }

    /** {@link #guard(Supplier)} with the provider calls tagged by the business key (see {@link Interaction}). */
    public static <T> T guard(String businessKey, Supplier<T> call) {
        return guard(() -> Interaction.within(businessKey, call));
    }

    public static <T> T guard(Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderException e) {
            switch (e.kind()) {
                case BUSINESS_REJECTION:
                    throw CamundaError.bpmnError("Err_" + camel(e.code()), e.getMessage());
                case TRANSIENT:
                    // retries null: the engine's count minus one; the same idempotency key is used on the retry
                    throw CamundaError.jobError(e.kind() + ": " + e.code() + ": " + e.getMessage(), null, null, JobErrors::retryBackoff, e);
                default:
                    // retries = 0 raises the incident now; the message names the provider's code, never a payload
                    throw CamundaError.jobError(e.kind() + ": " + e.getMessage(), null, 0);
            }
        }
    }

    /** The retries every service task of the models gives ({@code retries="5"}). */
    static final int MODEL_RETRIES = 5;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    /**
     * Exponential backoff by the retries the engine has left: 2 s after the first failure, then 4, 8, 16 s, at
     * most 60 s. Retrying at once (the client's default) spent all five retries in under a second while a
     * provider or Keycloak restarted, and turned a short outage into an incident.
     */
    static Duration retryBackoff(int retriesLeft) {
        int failures = Math.min(6, Math.max(1, MODEL_RETRIES - retriesLeft));
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << (failures - 1));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /** INSUFFICIENT_FUNDS to InsufficientFunds. */
    static String camel(String code) {
        StringBuilder out = new StringBuilder();
        for (String part : code.toLowerCase().split("[^a-z0-9]+")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }
}
