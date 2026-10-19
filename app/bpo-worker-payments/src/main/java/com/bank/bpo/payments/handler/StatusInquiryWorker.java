package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.SchemeStatusPort;
import com.bank.bpo.workerkit.JobErrors;
import com.bank.bpo.workerkit.OutputVariables;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** psg.status-inquiry: reads the gateway's record of the instruction; read-only, so it needs no idempotency key. */
@Component
public class StatusInquiryWorker {

    private static final Set<String> OUTPUTS = Set.of("schemeStatus");
    private final SchemeStatusPort schemes;

    public StatusInquiryWorker(SchemeStatusPort schemes) {
        this.schemes = schemes;
    }

    @JobWorker(type = "psg.status-inquiry", fetchVariables = {"rail", "sourceRef"})
    public Map<String, Object> handle(@Variable String rail, @Variable String sourceRef) {
        return JobErrors.guard(sourceRef, () -> OutputVariables.of(OUTPUTS,
            Map.of("schemeStatus", schemes.statusOf(rail, sourceRef).name())));
    }
}
