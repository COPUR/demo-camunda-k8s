package com.bank.bpo.exceptions.domain.catalog;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the exception case catalog: a situation that makes a payment, or a step of its process, need
 * someone's attention, where the model detects it, and how it is handled.
 *
 * @param routeTo           the review level a person decides at (OPERATIONS, SUPERVISOR) or the team that acts
 *                          (ENGINEERING for incidents, PAY for a hand-back)
 * @param allowedDecisions  the decisions a person may take; empty when no person decides
 * @param relatedErrors     the PEX codes the API answers with around this case
 */
public record ExceptionCaseDefinition(String code, String name, String title, String subDomain, String trigger,
                                      List<String> bpmnElements, String routeTo, List<String> allowedDecisions, String slaSource,
                                      String owner, List<String> relatedErrors, String runbook) {

    private static final Pattern CODE = Pattern.compile("EXC-\\d{3}");

    public ExceptionCaseDefinition {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("an exception case code is EXC- and three digits: " + code);
        }
        for (Object required : new Object[] {name, title, subDomain, trigger, routeTo, owner, runbook}) {
            Objects.requireNonNull(required, code + " misses a required field");
        }
        bpmnElements = List.copyOf(bpmnElements);
        allowedDecisions = List.copyOf(allowedDecisions);
        relatedErrors = List.copyOf(relatedErrors);
        slaSource = slaSource == null ? "" : slaSource;
    }
}
