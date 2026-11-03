package com.bank.bpo.exceptions.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionCatalogTest {

    static ExceptionCaseDefinition caseDefinition(String code) {
        return new ExceptionCaseDefinition(code, "SCHEME_STATUS_TIMEOUT", "Scheme status still unknown", "scheme-status",
            "lib-await-external-status ends with Err_StatusTimeout", List.of("BoundaryEvent_StatusTimeout"), "OPERATIONS",
            List.of("SETTLED", "REJECTED", "ESCALATE"), "pay-exception-routing: opsQueue, opsSla", "payments-operations",
            List.of("PEX-4001"), "Check the scheme record in the payments operations view.");
    }

    static ErrorDefinition errorDefinition(ErrorCode code) {
        return new ErrorDefinition(code.code(), code.name(), code.title(), code.category(), 404, code.retryable(), "case-management",
            "The payment has no exception case.", "Raise the case first.");
    }

    @Test
    void theCatalogAnswersByCode() {
        ExceptionCatalog catalog = new ExceptionCatalog("1.0.0", List.of(caseDefinition("EXC-001")),
            List.of(errorDefinition(ErrorCode.EXCEPTION_CASE_NOT_FOUND)));

        assertThat(catalog.version()).isEqualTo("1.0.0");
        assertThat(catalog.exceptionCase("EXC-001")).map(ExceptionCaseDefinition::name).contains("SCHEME_STATUS_TIMEOUT");
        assertThat(catalog.exceptionCase("EXC-404")).isEmpty();
        assertThat(catalog.error("PEX-3001")).map(ErrorDefinition::category).contains(ErrorCategory.NOT_FOUND);
        assertThat(catalog.error("PEX-0000")).isEmpty();
        assertThat(catalog.exceptionCases()).hasSize(1);
        assertThat(catalog.errors()).hasSize(1);
    }

    @Test
    void codesAreUniqueAndWellFormed() {
        assertThatThrownBy(() -> new ExceptionCatalog("1.0.0", List.of(caseDefinition("EXC-001"), caseDefinition("EXC-001")), List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EXC-001");
        assertThatThrownBy(() -> new ExceptionCatalog("1.0.0", List.of(), List.of(errorDefinition(ErrorCode.INTERNAL_ERROR),
            errorDefinition(ErrorCode.INTERNAL_ERROR)))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PEX-9999");
        assertThatThrownBy(() -> caseDefinition("CASE-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorDefinition("E1", "X", "t", ErrorCategory.INTERNAL, 500, false, "s", "d", "r"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorDefinition("PEX-1001", "X", "t", ErrorCategory.INTERNAL, 99, false, "s", "d", "r"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionCatalog(" ", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCatalogIsImmutable() {
        ExceptionCatalog catalog = new ExceptionCatalog("1.0.0", List.of(caseDefinition("EXC-001")), List.of());
        assertThatThrownBy(() -> catalog.exceptionCases().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.exceptionCases().getFirst().bpmnElements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
