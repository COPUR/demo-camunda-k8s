package com.bank.bpo.exceptions.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;
import org.junit.jupiter.api.Test;

class YamlCatalogSourceTest {

    @Test
    void theShippedCatalogLoads() {
        ExceptionCatalog catalog = new YamlCatalogSource(YamlCatalogSource.DEFAULT_RESOURCE).load();

        assertThat(catalog.version()).isEqualTo("1.0.0");
        assertThat(catalog.exceptionCases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(catalog.exceptionCase("EXC-001")).hasValueSatisfying(c -> {
            assertThat(c.bpmnElements()).contains("BoundaryEvent_StatusTimeout");
            assertThat(c.allowedDecisions()).containsExactly("SETTLED", "REJECTED", "ESCALATE");
        });
        assertThat(catalog.error("PEX-9001")).hasValueSatisfying(e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void aMissingResourceStopsTheStart() {
        assertThatThrownBy(() -> new YamlCatalogSource("catalog/none.yaml").load())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
    }

    @Test
    void anUnknownFieldIsATypoNotSomethingToIgnore() {
        assertThatThrownBy(() -> new YamlCatalogSource("catalog-fixtures/unknown-field.yaml").load())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void aMissingRequiredFieldStopsTheStart() {
        assertThatThrownBy(() -> new YamlCatalogSource("catalog-fixtures/missing-field.yaml").load())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void aDuplicateCodeStopsTheStart() {
        assertThatThrownBy(() -> new YamlCatalogSource("catalog-fixtures/duplicate-code.yaml").load())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PEX-1001");
    }
}
