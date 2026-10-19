package com.bank.bpo.payments.adapter;

import com.bank.bpo.payments.port.HoldPort;
import com.bank.bpo.workerkit.Ids;
import com.bank.bpo.workerkit.ProviderHttp;
import java.util.Map;

/** acc holds: capture (the pivot) and release (contracts/expectations/acc.yaml). */
public final class AccHoldAdapter implements HoldPort {

    private final ProviderHttp http;

    public AccHoldAdapter(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public String capture(String paymentId, String idempotencyKey) {
        String id = Ids.require("paymentId", paymentId);
        // No journalRef in a 2xx is a contract fault: the instance stops on an incident instead of telling pay and
        // the customer that the payment settled.
        return Ids.require("journalRef", http.post("/api/v1/holds/" + id + "/capture", Map.of(), idempotencyKey).get("journalRef"));
    }

    @Override
    public void release(String paymentId, String idempotencyKey) {
        http.post("/api/v1/holds/" + Ids.require("paymentId", paymentId) + "/release", Map.of(), idempotencyKey);
    }
}
