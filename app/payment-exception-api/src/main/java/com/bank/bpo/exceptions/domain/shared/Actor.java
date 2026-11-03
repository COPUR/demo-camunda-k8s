package com.bank.bpo.exceptions.domain.shared;

/**
 * Who acts: the token's principal (a person's username, or a service account for a system). Opaque to the domain;
 * it is printed into audit records and events, so control characters and unbounded lengths are refused.
 */
public record Actor(String subject) {

    private static final int MAX_LENGTH = 255;

    public Actor {
        if (subject == null || subject.isBlank() || subject.length() > MAX_LENGTH || subject.chars().anyMatch(Character::isISOControl)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "the token names no usable principal");
        }
    }
}
