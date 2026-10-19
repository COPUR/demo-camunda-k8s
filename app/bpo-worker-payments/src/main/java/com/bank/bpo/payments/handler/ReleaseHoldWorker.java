package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.HoldPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.JobErrors;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;

/** acc.release-hold: gives the held funds back after a scheme rejection. The key is paymentId:release. */
@Component
public class ReleaseHoldWorker {

    private final HoldPort holds;

    public ReleaseHoldWorker(HoldPort holds) {
        this.holds = holds;
    }

    @JobWorker(type = "acc.release-hold", fetchVariables = {"paymentId"})
    public Map<String, Object> handle(@Variable String paymentId) {
        JobErrors.guard(paymentId, () -> {
            holds.release(paymentId, IdempotencyKeys.of(paymentId, "release"));
            return null;
        });
        return Map.of();
    }
}
