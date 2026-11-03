package com.bank.bpo.exceptions.adapter.in.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Access tokens as Keycloak issues them for the demo realm: a user name, group paths and scopes. */
final class Tokens {

    static final String READ = "SCOPE_exceptions:read";
    static final String RAISE = "SCOPE_exceptions:raise";
    static final String DECIDE = "SCOPE_exceptions:decide";

    private Tokens() {
    }

    static JwtRequestPostProcessor token(String user, List<String> groups, String... scopes) {
        return jwt()
            .jwt(builder -> builder.subject("sub-" + user).claim("preferred_username", user).claim("groups", groups))
            .authorities(Arrays.stream(scopes).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    static JwtRequestPostProcessor raiser() {
        return token("svc-bpo-worker-payments", List.of(), RAISE, READ);
    }

    static JwtRequestPostProcessor reader() {
        return token("ops.ayse", List.of("/pay-tr-fast-ops"), READ);
    }

    static JwtRequestPostProcessor opsReviewer() {
        return token("ops.ayse", List.of("/pay-tr-fast-ops"), READ, DECIDE);
    }
}
