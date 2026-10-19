package com.bank.bpo.payments.adapter;

import com.bank.bpo.payments.port.NotificationPort;
import com.bank.bpo.workerkit.Ids;
import com.bank.bpo.workerkit.ProviderHttp;
import java.util.Map;
import java.util.Set;

/** ntf POST /api/v1/notifications (contracts/expectations/ntf.yaml): the payment and the purpose, never an address. */
public final class NtfNotificationAdapter implements NotificationPort {

    private static final Set<String> PURPOSES = Set.of("PAYMENT_SETTLED", "PAYMENT_REJECTED");
    private final ProviderHttp http;

    public NtfNotificationAdapter(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public String notify(String subjectRef, String purpose, String idempotencyKey) {
        Map<String, Object> request = Map.of("subjectRef", Ids.require("subjectRef", subjectRef),
            "purpose", Ids.oneOf("purpose", purpose, PURPOSES));
        return Ids.require("notificationRef", http.post("/api/v1/notifications", request, idempotencyKey).get("notificationRef"));
    }
}
