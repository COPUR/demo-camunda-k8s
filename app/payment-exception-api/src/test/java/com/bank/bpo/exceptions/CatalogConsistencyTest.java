package com.bank.bpo.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.bpo.exceptions.adapter.in.rest.problem.HttpStatusMapping;
import com.bank.bpo.exceptions.adapter.out.catalog.YamlCatalogSource;
import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.catalog.ErrorDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The catalog is documentation the code is held to: every error code the API can answer is catalogued with the same
 * name, title, category, retry flag and HTTP status, every reason the domain gives is an exception case, and every
 * BPMN element the catalog names exists in the deployed model.
 */
class CatalogConsistencyTest {

    static final ExceptionCatalog CATALOG = new YamlCatalogSource(YamlCatalogSource.DEFAULT_RESOURCE).load();
    /** The case's process and the status-inquiry library it calls: a case runs through both. */
    static final List<Path> MODELS = List.of(Path.of("../../models/pay-instant-exception.bpmn"), Path.of("../../models/lib-await-external-status.bpmn"));

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyErrorCodeIsCataloguedAsTheCodeDefinesIt(ErrorCode code) {
        ErrorDefinition entry = CATALOG.error(code.code()).orElseThrow(() -> new AssertionError(code + " is not in the catalog"));

        assertThat(entry.name()).isEqualTo(code.name());
        assertThat(entry.title()).isEqualTo(code.title());
        assertThat(entry.category()).isEqualTo(code.category());
        assertThat(entry.retryable()).isEqualTo(code.retryable());
        assertThat(entry.httpStatus()).isEqualTo(HttpStatusMapping.status(code));
    }

    @Test
    void theCatalogListsNoErrorTheCodeDoesNotKnow() {
        assertThat(CATALOG.errors()).extracting(ErrorDefinition::code)
            .containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
    }

    @ParameterizedTest
    @EnumSource(ExceptionReason.class)
    void everyReasonTheDomainGivesIsAnExceptionCase(ExceptionReason reason) {
        ExceptionCaseDefinition entry = CATALOG.exceptionCase(reason.catalogCode())
            .orElseThrow(() -> new AssertionError(reason + " is not in the catalog"));
        assertThat(entry.name()).isEqualTo(reason.name());
    }

    @Test
    void everyCaseNamesKnownErrorsAndDecisions() {
        Set<String> decisions = Arrays.stream(Decision.values()).filter(Decision::byPerson).map(Decision::name).collect(Collectors.toSet());
        for (ExceptionCaseDefinition entry : CATALOG.exceptionCases()) {
            assertThat(entry.relatedErrors()).as(entry.code()).allSatisfy(code -> assertThat(CATALOG.error(code)).isPresent());
            assertThat(entry.allowedDecisions()).as(entry.code()).allSatisfy(decision -> assertThat(decisions).contains(decision));
        }
    }

    @Test
    void everyBpmnElementTheCatalogNamesIsInTheModel() throws Exception {
        Set<String> elements = new java.util.HashSet<>();
        for (Path model : MODELS) {
            assertThat(model).as("the catalog is checked against the models in the repository").exists();
            Matcher ids = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(Files.readString(model));
            while (ids.find()) {
                elements.add(ids.group(1));
            }
        }
        for (ExceptionCaseDefinition entry : CATALOG.exceptionCases()) {
            assertThat(elements).as(entry.code()).containsAll(entry.bpmnElements());
        }
    }
}
