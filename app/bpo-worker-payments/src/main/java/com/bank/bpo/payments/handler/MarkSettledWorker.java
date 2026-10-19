package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.PaymentExceptionPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.JobErrors;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;

/** pay.mark-settled: tells pay the hold was captured, with the journal entry acc returned. */
@Component
public class MarkSettledWorker {

    private final PaymentExceptionPort payments;

    public MarkSettledWorker(PaymentExceptionPort payments) {
        this.payments = payments;
    }

    @JobWorker(type = "pay.mark-settled", fetchVariables = {"paymentId", "journalRef"})
    public Map<String, Object> handle(@Variable String paymentId, @Variable String journalRef) {
        JobErrors.guard(paymentId, () -> {
            payments.markSettled(paymentId, journalRef, IdempotencyKeys.of(paymentId, "mark-settled"));
            return null;
        });
        return Map.of();
    }
}
