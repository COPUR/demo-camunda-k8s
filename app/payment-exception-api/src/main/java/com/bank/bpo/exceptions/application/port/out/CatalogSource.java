package com.bank.bpo.exceptions.application.port.out;

import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;

/** Where the catalog is kept (a versioned file in the image). */
@FunctionalInterface
public interface CatalogSource {

    ExceptionCatalog load();
}
