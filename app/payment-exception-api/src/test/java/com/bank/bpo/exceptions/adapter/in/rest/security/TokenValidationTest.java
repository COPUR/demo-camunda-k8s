package com.bank.bpo.exceptions.adapter.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenValidationTest {

    static final String ISSUER = "https://keycloak.test/auth/realms/camunda-platform";
    final OAuth2TokenValidator<Jwt> validator = TokenValidation.validator(ISSUER, "payment-exception-api");

    static Jwt token(String issuer, List<String> audience, Instant expiresAt) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("ops1").issuer(issuer).audience(audience)
            .issuedAt(expiresAt.minusSeconds(300)).expiresAt(expiresAt).build();
    }

    @Test
    void acceptsATokenOfTheRealmForThisApi() {
        assertThat(validator.validate(token(ISSUER, List.of("payment-exception-api", "account"), Instant.now().plusSeconds(60))).hasErrors()).isFalse();
    }

    @Test
    void refusesATokenForAnotherServiceOfTheRealm() {
        assertThat(validator.validate(token(ISSUER, List.of("zeebe-api"), Instant.now().plusSeconds(60))).hasErrors()).isTrue();
    }

    @Test
    void refusesATokenOfAnotherIssuer() {
        assertThat(validator.validate(token("https://evil.test/realms/x", List.of("payment-exception-api"), Instant.now().plusSeconds(60))).hasErrors())
            .isTrue();
    }

    @Test
    void allowsThirtySecondsOfClockSkewAndNoMore() {
        assertThat(validator.validate(token(ISSUER, List.of("payment-exception-api"), Instant.now().minusSeconds(10))).hasErrors()).isFalse();
        assertThat(validator.validate(token(ISSUER, List.of("payment-exception-api"), Instant.now().minusSeconds(60))).hasErrors()).isTrue();
    }
}
