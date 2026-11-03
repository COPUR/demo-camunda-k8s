package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;

/** The case's concurrency token as a strong HTTP entity tag, and back. */
final class ETags {

    private ETags() {
    }

    static String of(ExceptionCase exceptionCase) {
        return "\"" + exceptionCase.concurrencyToken() + "\"";
    }

    /**
     * The token an If-Match header names, or null when it names none. If-Match compares strongly (RFC 9110), so a
     * weak tag never matches, and "*" is not accepted: a decision must name the view it was taken on.
     */
    static String token(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String first = ifMatch.split(",")[0].trim();
        if (first.equals("*")) {
            return null;
        }
        if (first.length() >= 2 && first.startsWith("\"") && first.endsWith("\"")) {
            return first.substring(1, first.length() - 1);
        }
        return first;
    }
}
