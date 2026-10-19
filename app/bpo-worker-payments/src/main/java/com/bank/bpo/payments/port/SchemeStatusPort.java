package com.bank.bpo.payments.port;

/** psg: the rail gateway's record of an instruction. Read only; nothing is ever sent to the scheme again. */
public interface SchemeStatusPort {

    enum Status { ACSC, RJCT, PDNG }

    Status statusOf(String rail, String paymentId);
}
