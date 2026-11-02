package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Instant;

/** The workflow took the decision; pay's outcome of record follows asynchronously and shows on the case. */
public record DecisionReceipt(PaymentId paymentId, ReviewTaskId reviewTaskId, ReviewLevel level, Decision decision, Instant submittedAt) {
}
