package com.bank.bpo.exceptions.application.port.out;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.util.Optional;

/**
 * Reads cases from their system of record. The adapter translates the engine's vocabulary (process instances, user
 * tasks, element ids) into the case-management language; nothing above it knows a BPMN element id.
 */
public interface ExceptionCaseRepository {

    Optional<ExceptionCase> findByPaymentId(PaymentId paymentId);

    CasePage findInReview(CaseQuery query);
}
