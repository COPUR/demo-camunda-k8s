package com.bank.bpo.workerkit;

import io.camunda.client.exception.CamundaError;
import java.util.function.Supplier;

/**
 * Turns a provider failure into what the engine should do with the job (05 section 7, point 4):
 * a business rejection becomes a BPMN error the model catches, a transient failure fails the job so it
 * is retried with the same idempotency key, and a bug or a wrong contract raises an incident at once.
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
                    throw e;
                default:
                    // retries = 0 raises the incident now; the message names the provider's code, never a payload
                    throw CamundaError.jobError(e.kind() + ": " + e.getMessage(), null, 0);
            }
        }
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
