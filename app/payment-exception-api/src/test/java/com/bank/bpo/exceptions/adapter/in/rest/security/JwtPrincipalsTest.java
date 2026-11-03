package com.bank.bpo.exceptions.adapter.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtPrincipalsTest {

    final JwtPrincipals principals = new JwtPrincipals("preferred_username", "groups");

    static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("5c1e");
    }

    @Test
    void theActorIsTheUserNameTasklistShowsAndTheSubjectWithoutOne() {
        assertThat(principals.actor(token().claim("preferred_username", "ops.ayse").build()).subject()).isEqualTo("ops.ayse");
        assertThat(principals.actor(token().build()).subject()).isEqualTo("5c1e");
    }

    @Test
    void theReviewersQueuesAreTheLastSegmentsOfTheirGroupPaths() {
        Jwt jwt = token().claim("groups", List.of("/pay-tr-fast-ops", "pay-tr-fast-supervisor", "/Not A Queue", "/")).build();
        assertThat(principals.reviewer(jwt).queues()).containsExactlyInAnyOrder(QueueName.of("pay-tr-fast-ops"), QueueName.of("pay-tr-fast-supervisor"));
        assertThat(principals.reviewer(token().claim("groups", "pay-tr-fast-ops").build()).queues()).isEmpty();
        assertThat(principals.reviewer(token().build()).queues()).isEmpty();
    }

    @Test
    void noTokenIsNoActor() {
        assertThatThrownBy(() -> principals.actor(null))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
