package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.BrowseExceptionCatalog;
import com.bank.bpo.exceptions.application.port.out.CatalogSource;
import com.bank.bpo.exceptions.domain.catalog.ErrorDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;

/** The catalog is read once at start-up: a broken catalog stops the service from starting instead of failing later. */
public final class ExceptionCatalogService implements BrowseExceptionCatalog {

    private final ExceptionCatalog catalog;

    public ExceptionCatalogService(CatalogSource source) {
        this.catalog = source.load();
    }

    @Override
    public ExceptionCatalog catalog() {
        return catalog;
    }

    @Override
    public ErrorDefinition error(String code) {
        return catalog.error(code).orElseThrow(() -> notFound(code));
    }

    @Override
    public ExceptionCaseDefinition exceptionCase(String code) {
        return catalog.exceptionCase(code).orElseThrow(() -> notFound(code));
    }

    private static DomainException notFound(String code) {
        return new DomainException(ErrorCode.CATALOG_ENTRY_NOT_FOUND, "the catalog has no entry " + code);
    }
}
