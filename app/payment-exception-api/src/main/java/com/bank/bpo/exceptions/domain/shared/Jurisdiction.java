package com.bank.bpo.exceptions.domain.shared;

import java.util.regex.Pattern;

/** The country whose rules and data residency apply, as an ISO 3166-1 alpha-2 code (TR for the FAST demo). */
public record Jurisdiction(String code) {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");

    public Jurisdiction {
        if (code == null || !ALPHA_2.matcher(code).matches()) {
            throw new DomainException(ErrorCode.INVALID_JURISDICTION, "jurisdiction must be an ISO 3166-1 alpha-2 code such as TR");
        }
    }

    public static Jurisdiction of(String code) {
        return new Jurisdiction(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
