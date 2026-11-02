package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import java.util.List;
import java.util.Optional;

/** One page of the work queue; {@code nextPageToken} is empty on the last page. */
public record CasePage(List<ExceptionCase> items, Optional<String> nextPageToken) {

    public CasePage {
        items = List.copyOf(items);
    }
}
