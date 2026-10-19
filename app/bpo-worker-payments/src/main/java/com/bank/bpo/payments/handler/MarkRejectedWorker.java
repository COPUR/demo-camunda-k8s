package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.PaymentExceptionPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.JobErrors;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;

/** pay.mark-rejected: tells pay the hold was released after the scheme rejected the instruction. */
@Component
public class MarkRejectedWorker {

    private final PaymentExceptionPort payments;

    public MarkRejectedWorker(PaymentExceptionPort payments) {
        this.payments = payments;
    }

    @JobWorker(type = "pay.mark-rejected", fetchVariables = {"paymentId"})
    public Map<String, Object> handle(@Variable String paymentId) {
        JobErrors.guard(paymentId, () -> {
            payments.markRejected(paymentId, IdempotencyKeys.of(paymentId, "mark-rejected"));
            return null;
        });
        return Map.of();
    }
}
