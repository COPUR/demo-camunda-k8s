package com.bank.bpo.exceptions.application.port.in;

import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The work queue query: cases in review, optionally for one queue or level, one page at a time. The page token is
 * the opaque cursor of the previous page (base64url, bounded), never an offset: pages stay stable while cases move.
 */
public record CaseQuery(Optional<QueueName> queue, Optional<ReviewLevel> level, int pageSize, Optional<String> pageToken) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,1024}");

    public CaseQuery {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pageToken, "pageToken");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "page_size is 1 to " + MAX_PAGE_SIZE);
        }
        if (pageToken.isPresent() && !TOKEN.matcher(pageToken.get()).matches()) {
            throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "page_token is not a token this API issued");
        }
    }

    public static CaseQuery firstPage(Optional<QueueName> queue, Optional<ReviewLevel> level, Integer pageSize) {
        return new CaseQuery(queue, level, pageSize == null ? DEFAULT_PAGE_SIZE : pageSize, Optional.empty());
    }
}
