package com.bank.bpo.exceptions.application.port.in;

/** Use case: a reviewer records what the scheme record shows, or escalates. */
public interface DecideExceptionCase {

    DecisionResult decide(DecideCommand command);
}
