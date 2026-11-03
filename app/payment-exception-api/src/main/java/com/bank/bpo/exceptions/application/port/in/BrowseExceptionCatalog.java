package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.catalog.ErrorDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;

/** Use case: read the exception case catalog and the exception repository (also the target of problem type URIs). */
public interface BrowseExceptionCatalog {

    ExceptionCatalog catalog();

    ErrorDefinition error(String code);

    ExceptionCaseDefinition exceptionCase(String code);
}
