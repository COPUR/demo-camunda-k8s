package com.bank.bpo.payments.adapter;

import com.bank.bpo.payments.port.PaymentExceptionPort;
import com.bank.bpo.workerkit.Ids;
import com.bank.bpo.workerkit.ProviderHttp;
import java.util.Map;
import java.util.Set;

/** pay payment exceptions: record the decision, mark settled or rejected (contracts/expectations/pay.yaml). */
public final class PayExceptionAdapter implements PaymentExceptionPort {

    private static final Set<String> DECISIONS = Set.of("SETTLED", "REJECTED", "EXPIRED");
    private static final Set<String> OUTCOMES = Set.of("SETTLE", "RELEASE", "HANDED_BACK");
    private final ProviderHttp http;

    public PayExceptionAdapter(ProviderHttp http) {
        this.http = http;
    }

    private static String base(String paymentId) {
        return "/api/v1/payments/" + Ids.require("paymentId", paymentId) + "/exception";
    }

    @Override
    public Outcome record(String paymentId, String decision, String idempotencyKey) {
        Map<String, Object> answer = http.post(base(paymentId) + "/decision",
            Map.of("decision", Ids.oneOf("decision", decision, DECISIONS)), idempotencyKey);
        return Outcome.valueOf(Ids.oneOf("outcome", answer.get("outcome"), OUTCOMES));
    }

    @Override
    public void markSettled(String paymentId, String journalRef, String idempotencyKey) {
        String path = base(paymentId) + "/settle";
        http.post(path, Map.of("journalRef", Ids.require("journalRef", journalRef)), idempotencyKey);
    }

    @Override
    public void markRejected(String paymentId, String idempotencyKey) {
        http.post(base(paymentId) + "/reject", Map.of(), idempotencyKey);
    }
}
