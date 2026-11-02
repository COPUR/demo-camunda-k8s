package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.contract.v1.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.google.protobuf.Message;
import java.util.Optional;

/** Reads the parts of a request that are not the body: headers and query parameters, each into a value object. */
final class Requests {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private Requests() {
    }

    static IdempotencyKey idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "send an Idempotency-Key header (a UUID) with every command");
        }
        return IdempotencyKey.of(header.trim());
    }

    /**
     * The JSON parser already refuses unknown fields; a binary message keeps them aside, so they are refused here.
     * A field this version does not know is a client built against another contract, not something to drop.
     */
    static <M extends Message> M knownFieldsOnly(M message) {
        if (!message.getUnknownFields().asMap().isEmpty()) {
            throw new DomainException(ErrorCode.MALFORMED_REQUEST, "the message carries fields this API version does not define");
        }
        return message;
    }

    static CaseQuery caseQuery(String queue, String reviewLevel, String pageSize, String pageToken) {
        Optional<QueueName> queueName = blank(queue) ? Optional.empty() : Optional.of(new QueueName(queue));
        return new CaseQuery(queueName, reviewLevel(reviewLevel), pageSize(pageSize), Optional.ofNullable(blank(pageToken) ? null : pageToken));
    }

    private static Optional<com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel> reviewLevel(String value) {
        if (blank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ProtoMapper.toDomain(ReviewLevel.valueOf(value)));
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "reviewLevel is REVIEW_LEVEL_OPERATIONS or REVIEW_LEVEL_SUPERVISOR");
        }
    }

    private static int pageSize(String value) {
        if (blank(value)) {
            return CaseQuery.DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "pageSize is a number from 1 to " + CaseQuery.MAX_PAGE_SIZE);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
