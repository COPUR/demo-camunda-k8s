package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.regex.Pattern;

/**
 * The caller's key for one command (the {@code Idempotency-Key} header). A retry with the same key and the same
 * request gets the first answer again; the same key with another request is refused.
 */
public record IdempotencyKey(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    public IdempotencyKey {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new DomainException(ErrorCode.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key is 8 to 64 of A-Z, a-z, 0-9 and _ . : - (a UUID fits)");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
