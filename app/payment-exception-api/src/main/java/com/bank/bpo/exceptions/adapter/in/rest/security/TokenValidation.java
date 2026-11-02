package com.bank.bpo.exceptions.adapter.in.rest.security;

import java.time.Duration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * What makes a token acceptable besides its RS256 signature by the realm's keys: not expired (30 s of clock skew),
 * issued by the realm, and meant for this API. A token for another service of the realm is refused here.
 */
public final class TokenValidation {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private TokenValidation() {
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(CLOCK_SKEW), new JwtIssuerValidator(issuer),
            new JwtAudienceValidator(audience));
    }
}
