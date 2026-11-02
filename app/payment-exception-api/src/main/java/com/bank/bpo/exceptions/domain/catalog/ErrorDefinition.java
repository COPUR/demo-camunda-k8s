package com.bank.bpo.exceptions.domain.catalog;

import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the exception repository: an error the API answers with, documented for the people who call it.
 * The HTTP status is part of the documentation of the published API; the REST adapter's mapping is tested against it.
 */
public record ErrorDefinition(String code, String name, String title, ErrorCategory category, int httpStatus, boolean retryable,
                              String subDomain, String description, String remediation) {

    private static final Pattern CODE = Pattern.compile("PEX-\\d{4}");

    public ErrorDefinition {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("an error code is PEX- and four digits: " + code);
        }
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException(code + " documents an HTTP status outside 400..599: " + httpStatus);
        }
        for (Object required : new Object[] {name, title, category, subDomain, description, remediation}) {
            Objects.requireNonNull(required, code + " misses a required field");
        }
    }
}
