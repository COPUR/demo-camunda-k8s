package com.bank.bpo.exceptions.adapter.in.rest.security;

import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.Reviewer;
import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the domain's identities from a validated token: the actor from the principal claim (Keycloak's
 * preferred_username, the name Tasklist shows as assignee; sub when it is absent), the reviewer's queues from the
 * groups claim. Nothing else of the token reaches the domain.
 */
public final class JwtPrincipals {

    private final String principalClaim;
    private final String groupsClaim;

    public JwtPrincipals(String principalClaim, String groupsClaim) {
        this.principalClaim = principalClaim;
        this.groupsClaim = groupsClaim;
    }

    public Actor actor(Jwt jwt) {
        if (jwt == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "a valid bearer token is required");
        }
        String name = Optional.ofNullable(jwt.getClaimAsString(principalClaim)).orElse(jwt.getSubject());
        return new Actor(name);
    }

    public Reviewer reviewer(Jwt jwt) {
        Actor actor = actor(jwt);
        Object groups = jwt.getClaims().get(groupsClaim);
        Set<QueueName> queues = groups instanceof Collection<?> values
            ? values.stream().map(String::valueOf).map(QueueName::parse).flatMap(Optional::stream).collect(Collectors.toSet())
            : Set.of();
        return new Reviewer(actor, queues);
    }
}
