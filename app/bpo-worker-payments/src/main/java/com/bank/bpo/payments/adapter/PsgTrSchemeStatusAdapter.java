package com.bank.bpo.payments.adapter;

import com.bank.bpo.payments.port.SchemeStatusPort;
import com.bank.bpo.workerkit.Ids;
import com.bank.bpo.workerkit.ProviderHttp;
import java.util.Set;

/** psg-tr GET /api/v1/psg-tr/fast/instructions/{paymentId}/status (contracts/expectations/psg-tr.yaml). */
public final class PsgTrSchemeStatusAdapter implements SchemeStatusPort {

    // Only FAST goes through psg-tr; another rail reaching this worker is a wiring fault (an incident), not a guess.
    private static final Set<String> RAILS = Set.of("FAST");
    private static final Set<String> STATUSES = Set.of("ACSC", "RJCT", "PDNG");
    private final ProviderHttp http;

    public PsgTrSchemeStatusAdapter(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Status statusOf(String rail, String paymentId) {
        Ids.oneOf("rail", rail, RAILS);
        String id = Ids.require("paymentId", paymentId);
        // A status this adapter does not know is a contract fault, never a settlement or a rejection.
        return Status.valueOf(Ids.oneOf("schemeStatus",
            http.get("/api/v1/psg-tr/fast/instructions/" + id + "/status").get("schemeStatus"), STATUSES));
    }
}
