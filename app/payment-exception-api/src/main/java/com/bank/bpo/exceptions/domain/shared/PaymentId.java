package com.bank.bpo.exceptions.domain.shared;

import java.util.regex.Pattern;

/**
 * The payment an exception case is about, and the case's identity (one case per payment). Only synthetic
 * {@code demo-tr-*} ids are valid: the demo must never carry a real payment reference, so an IBAN or a real
 * end-to-end id is refused at the edge instead of being stored or forwarded.
 */
public record PaymentId(String value) {

    private static final Pattern SYNTHETIC = Pattern.compile("demo-tr-[a-z0-9][a-z0-9-]{0,39}");

    public PaymentId {
        if (value == null || !SYNTHETIC.matcher(value).matches()) {
            throw new DomainException(ErrorCode.INVALID_PAYMENT_ID,
                "paymentId must be a synthetic id: demo-tr- followed by 1 to 40 of a-z, 0-9 and -");
        }
    }

    public static PaymentId of(String value) {
        return new PaymentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
