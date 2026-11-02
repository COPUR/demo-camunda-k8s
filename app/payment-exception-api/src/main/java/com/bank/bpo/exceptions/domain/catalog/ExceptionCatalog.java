package com.bank.bpo.exceptions.domain.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Aggregate root of the catalog sub-domain (supporting): the exception cases of the P2 process and the exception
 * repository of the API, as one versioned piece of reference data. It refers to other sub-domains by code only.
 */
public record ExceptionCatalog(String version, List<ExceptionCaseDefinition> exceptionCases, List<ErrorDefinition> errors) {

    public ExceptionCatalog {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("the catalog has a version");
        }
        exceptionCases = List.copyOf(exceptionCases);
        errors = List.copyOf(errors);
        requireUnique(exceptionCases, ExceptionCaseDefinition::code);
        requireUnique(errors, ErrorDefinition::code);
    }

    public Optional<ExceptionCaseDefinition> exceptionCase(String code) {
        return exceptionCases.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public Optional<ErrorDefinition> error(String code) {
        return errors.stream().filter(e -> e.code().equals(code)).findFirst();
    }

    private static <T> void requireUnique(List<T> entries, Function<T, String> code) {
        Set<String> seen = new HashSet<>();
        for (T entry : entries) {
            if (!seen.add(code.apply(entry))) {
                throw new IllegalArgumentException("the catalog lists " + code.apply(entry) + " twice");
            }
        }
    }
}
