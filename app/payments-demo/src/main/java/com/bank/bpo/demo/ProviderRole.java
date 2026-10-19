package com.bank.bpo.demo;

import java.util.Locale;

/**
 * The provider a simulator service stands in for in the microservices demo: the only path prefix it serves and the
 * audience a caller's token must carry for it (docs/identity-requests.md; psg-tr and ntf have no client yet, so the
 * demo uses the working names svc-psg-tr and svc-ntf).
 */
enum ProviderRole {
    PSG_TR("psg-tr", "/api/v1/psg-tr/", "svc-psg-tr"),
    ACC("acc", "/api/v1/holds/", "svc-acc-accounts-ledger"),
    PAY("pay", "/api/v1/payments/", "svc-pay-initiation-settlement"),
    NTF("ntf", "/api/v1/notifications", "svc-ntf");

    private final String code;
    private final String pathPrefix;
    private final String audience;

    ProviderRole(String code, String pathPrefix, String audience) {
        this.code = code;
        this.pathPrefix = pathPrefix;
        this.audience = audience;
    }

    static ProviderRole of(String code) {
        for (ProviderRole role : values()) {
            if (role.code.equals(code.toLowerCase(Locale.ROOT))) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown provider role: " + code);
    }

    String code() {
        return code;
    }

    String audience() {
        return audience;
    }

    /** Raw paths only, so an encoded "/.." cannot reach another provider's operations. */
    boolean serves(String rawPath) {
        return rawPath.startsWith(pathPrefix) && !rawPath.contains("..") && !rawPath.contains("%");
    }
}
