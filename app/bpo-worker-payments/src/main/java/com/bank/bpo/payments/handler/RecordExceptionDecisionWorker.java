package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.PaymentExceptionPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.JobErrors;
import com.bank.bpo.workerkit.OutputVariables;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * pay.record-exception-decision: posts the operations decision, or EXPIRED set by the SLA expiry event, to pay and
 * returns pay's outcome. ESCALATE or a missing decision is refused by the adapter as a contract fault (an incident).
 */
@Component
public class RecordExceptionDecisionWorker {

    private static final Set<String> OUTPUTS = Set.of("exceptionOutcome");
    private final PaymentExceptionPort payments;

    public RecordExceptionDecisionWorker(PaymentExceptionPort payments) {
        this.payments = payments;
    }

    @JobWorker(type = "pay.record-exception-decision", fetchVariables = {"paymentId", "exceptionDecision"})
    public Map<String, Object> handle(@Variable String paymentId, @Variable String exceptionDecision) {
        return JobErrors.guard(paymentId, () -> OutputVariables.of(OUTPUTS, Map.of("exceptionOutcome",
            payments.record(paymentId, exceptionDecision, IdempotencyKeys.of(paymentId, "record-exception-decision")).name())));
    }
}
