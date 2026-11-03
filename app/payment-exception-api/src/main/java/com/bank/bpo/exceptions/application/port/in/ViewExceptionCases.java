package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.shared.PaymentId;

/** Use case: read one case, or the work queue of cases waiting for a decision. */
public interface ViewExceptionCases {

    ExceptionCase get(PaymentId paymentId);

    CasePage inReview(CaseQuery query);
}
