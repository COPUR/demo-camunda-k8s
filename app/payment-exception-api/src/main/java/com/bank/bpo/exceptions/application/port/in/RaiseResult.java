package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;

/** @param replayed true when this is the stored answer to an earlier request with the same key */
public record RaiseResult(ExceptionCase exceptionCase, boolean replayed) {
}
