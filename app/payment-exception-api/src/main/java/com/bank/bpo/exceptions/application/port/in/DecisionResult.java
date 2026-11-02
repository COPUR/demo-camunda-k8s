package com.bank.bpo.exceptions.application.port.in;

/** @param replayed true when this is the stored receipt of an earlier request with the same key */
public record DecisionResult(DecisionReceipt receipt, boolean replayed) {
}
