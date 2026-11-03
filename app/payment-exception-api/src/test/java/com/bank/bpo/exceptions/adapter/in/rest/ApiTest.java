package com.bank.bpo.exceptions.adapter.in.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The whole HTTP edge (filters, security, controllers, converters, error handling) over in-memory adapters: no
 * engine, no broker, no network. One configuration for every API test, so they share one Spring context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
    "camunda.client.enabled=false",
    "management.server.port=",
    "bpo.exceptions.security.issuer=https://keycloak.test/auth/realms/camunda-platform",
    "bpo.exceptions.security.jwk-set-uri=https://keycloak.test/auth/realms/camunda-platform/protocol/openid-connect/certs",
    "bpo.exceptions.http.rate-limit.capacity=10000",
    "bpo.exceptions.http.rate-limit.refill-per-second=10000",
    "bpo.exceptions.http.max-request-bytes=2048"
})
@AutoConfigureMockMvc
@Import(FakeAdapters.class)
@interface ApiTest {
}
