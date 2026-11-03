package com.bank.bpo.exceptions.adapter.out.catalog;

import com.bank.bpo.exceptions.application.port.out.CatalogSource;
import com.bank.bpo.exceptions.domain.catalog.ErrorDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;
import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads the catalog from a YAML resource on the classpath. Strict: an unknown or missing field is an error, so a
 * typo in the file stops the service at start-up rather than silently dropping documentation.
 */
public final class YamlCatalogSource implements CatalogSource {

    public static final String DEFAULT_RESOURCE = "catalog/exception-catalog.yaml";

    record File(String version, List<CaseEntry> exceptionCases, List<ErrorEntry> errors) {
    }

    record CaseEntry(String code, String name, String title, String subDomain, String trigger, List<String> bpmnElements, String routeTo,
                     List<String> allowedDecisions, String slaSource, String owner, List<String> relatedErrors, String runbook) {
    }

    record ErrorEntry(String code, String name, String title, ErrorCategory category, Integer httpStatus, Boolean retryable, String subDomain,
                      String description, String remediation) {
    }

    private final String resource;
    private final YAMLMapper yaml = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    public YamlCatalogSource(String resource) {
        this.resource = resource;
    }

    @Override
    public ExceptionCatalog load() {
        try (InputStream in = YamlCatalogSource.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("catalog resource " + resource + " is missing");
            }
            File file = yaml.readValue(in, File.class);
            return new ExceptionCatalog(file.version(), file.exceptionCases().stream().map(YamlCatalogSource::toDomain).toList(),
                file.errors().stream().map(YamlCatalogSource::toDomain).toList());
        } catch (IOException | JacksonException | NullPointerException | IllegalArgumentException e) {
            throw new IllegalStateException("catalog resource " + resource + " is invalid: " + e.getMessage(), e);
        }
    }

    private static ExceptionCaseDefinition toDomain(CaseEntry e) {
        return new ExceptionCaseDefinition(e.code(), e.name(), e.title(), e.subDomain(), e.trigger(), e.bpmnElements(), e.routeTo(),
            e.allowedDecisions(), e.slaSource(), e.owner(), e.relatedErrors(), e.runbook());
    }

    private static ErrorDefinition toDomain(ErrorEntry e) {
        return new ErrorDefinition(e.code(), e.name(), e.title(), e.category(), e.httpStatus(), e.retryable(), e.subDomain(), e.description(),
            e.remediation());
    }
}
