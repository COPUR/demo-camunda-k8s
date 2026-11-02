package com.bank.bpo.exceptions.adapter.in.rest;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RFC 9728: the 401 challenge points clients at the protected resource metadata, so that document must say where
 * tokens come from and which scopes exist, and must not claim mTLS-bound tokens this API does not check.
 */
@ApiTest
class ProtectedResourceMetadataTest {

    @Autowired
    MockMvc mvc;

    @Test
    void namesTheIssuerTheScopesAndOnlyTheHeaderForBearerTokens() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resource_name").value("Payment exception API"))
            .andExpect(jsonPath("$.authorization_servers", contains("https://keycloak.test/auth/realms/camunda-platform")))
            .andExpect(jsonPath("$.scopes_supported", containsInAnyOrder("exceptions:read", "exceptions:raise", "exceptions:decide")))
            .andExpect(jsonPath("$.bearer_methods_supported", contains("header")))
            .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    @Test
    void theChallengeOfAnUnauthenticatedCallPointsAtIt() throws Exception {
        mvc.perform(get("/api/v1/exception-catalog"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("/.well-known/oauth-protected-resource")));
    }
}
