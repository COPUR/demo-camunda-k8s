package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.HoldPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.JobErrors;
import com.bank.bpo.workerkit.OutputVariables;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** acc.capture-hold: the pivot step of P2. The key is paymentId:capture, so a retry or a restart replays. */
@Component
public class CaptureHoldWorker {

    private static final Set<String> OUTPUTS = Set.of("journalRef");
    private final HoldPort holds;

    public CaptureHoldWorker(HoldPort holds) {
        this.holds = holds;
    }

    @JobWorker(type = "acc.capture-hold", fetchVariables = {"paymentId"})
    public Map<String, Object> handle(@Variable String paymentId) {
        return JobErrors.guard(paymentId, () -> OutputVariables.of(OUTPUTS,
            Map.of("journalRef", holds.capture(paymentId, IdempotencyKeys.of(paymentId, "capture")))));
    }
}
