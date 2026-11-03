package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import java.util.Set;

/**
 * Data residency: a deployment opens cases only for the jurisdictions it serves (the workers' C8-K4 rule, "runs only
 * in the cluster of the jurisdiction it serves"). The TR deployment serves TR.
 */
public record JurisdictionPolicy(Set<Jurisdiction> served) {

    public JurisdictionPolicy {
        if (served == null || served.isEmpty()) {
            throw new IllegalArgumentException("a deployment serves at least one jurisdiction");
        }
        served = Set.copyOf(served);
    }

    public void requireServed(Jurisdiction jurisdiction) {
        if (!served.contains(jurisdiction)) {
            throw new DomainException(ErrorCode.JURISDICTION_NOT_SERVED, "this deployment does not open cases for " + jurisdiction);
        }
    }
}
