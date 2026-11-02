package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.util.Objects;

/** What it takes to open a case: the process gets exactly these three values and no payment detail. */
public record NewExceptionCase(PaymentId paymentId, Jurisdiction jurisdiction, Rail rail) {

    public NewExceptionCase {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(rail, "rail");
    }
}
