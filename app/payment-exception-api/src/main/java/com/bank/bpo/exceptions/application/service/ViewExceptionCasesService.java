package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.application.port.in.ViewExceptionCases;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.PaymentId;

public final class ViewExceptionCasesService implements ViewExceptionCases {

    private final ExceptionCaseRepository cases;

    public ViewExceptionCasesService(ExceptionCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public ExceptionCase get(PaymentId paymentId) {
        return cases.findByPaymentId(paymentId)
            .orElseThrow(() -> new DomainException(ErrorCode.EXCEPTION_CASE_NOT_FOUND, "payment " + paymentId + " has no exception case"));
    }

    @Override
    public CasePage inReview(CaseQuery query) {
        return cases.findInReview(query);
    }
}
