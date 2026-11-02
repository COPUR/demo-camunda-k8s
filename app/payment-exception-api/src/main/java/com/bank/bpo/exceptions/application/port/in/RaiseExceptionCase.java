package com.bank.bpo.exceptions.application.port.in;

/** Use case: pay hands a payment whose scheme status is unknown over to exception handling. */
public interface RaiseExceptionCase {

    RaiseResult raise(RaiseCommand command);
}
