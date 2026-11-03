package com.bank.bpo.exceptions.config;

import com.bank.bpo.exceptions.adapter.in.rest.filter.RateLimitFilter;
import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.adapter.in.rest.security.JwtPrincipals;
import com.bank.bpo.exceptions.adapter.in.rest.security.TokenValidation;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.DispatcherType;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.client.RestTemplate;

/**
 * Resource server: every API call carries a Keycloak access token for this API's audience, and each operation needs
 * its scope. Object-level rules (only a member of the routed queue decides) are the domain's. Stateless, no cookies,
 * so no CSRF surface; responses are never framed. Anything not listed is refused (deny by default).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String READ = "SCOPE_exceptions:read";
    static final String RAISE = "SCOPE_exceptions:raise";
    static final String DECIDE = "SCOPE_exceptions:decide";

    /** The OAuth scope behind an authority: SCOPE_exceptions:read is the scope exceptions:read. */
    static String scopeName(String authority) {
        return authority.substring("SCOPE_".length());
    }

    @Bean
    JwtPrincipals jwtPrincipals(ExceptionApiProperties properties) {
        return new JwtPrincipals(properties.security().principalClaim(), properties.security().groupsClaim());
    }

    /** Health and info only, without a token, for the kubelet's probes. */
    @Bean
    @Order(1)
    SecurityFilterChain probes(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/health", "/actuator/health/**", "/actuator/info")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, ProblemWriter problems, ExceptionApiProperties properties) throws Exception {
        ExceptionApiProperties.RateLimit limit = properties.http().rateLimit();
        // RFC 6750's WWW-Authenticate challenge (invalid_token, insufficient_scope) from Spring's own handlers, then
        // the catalogued problem as the body.
        AuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        AccessDeniedHandler scope = new BearerTokenAccessDeniedHandler();
        AuthenticationEntryPoint unauthenticated = (request, response, e) -> {
            bearer.commence(request, response, e);
            problems.write(request, response, ErrorCode.UNAUTHENTICATED, "a valid bearer token for this API is required");
        };
        AccessDeniedHandler forbidden = (request, response, e) -> {
            scope.handle(request, response, e);
            problems.write(request, response, ErrorCode.FORBIDDEN, "the token does not grant this operation");
        };
        http.authorizeHttpRequests(auth -> auth
                // The error page renders the problem of a request that was already authorized or refused.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/exception-catalog", "/api/v1/exception-catalog/**").hasAuthority(READ)
                .requestMatchers(HttpMethod.GET, "/api/v1/payment-exceptions", "/api/v1/payment-exceptions/**").hasAuthority(READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/payment-exceptions").hasAuthority(RAISE)
                .requestMatchers(HttpMethod.POST, "/api/v1/payment-exceptions/*/decisions").hasAuthority(DECIDE)
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> { })
                // RFC 9728 document the 401 challenge points at: where tokens come from and which scopes exist.
                // This API does not bind tokens to client certificates, so it must not advertise that it does.
                .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(document -> document
                    .resourceName("Payment exception API")
                    .authorizationServer(properties.security().issuer())
                    .scopes(scopes -> scopes.addAll(List.of(scopeName(READ), scopeName(RAISE), scopeName(DECIDE))))
                    .tlsClientCertificateBoundAccessTokens(false)))
                .authenticationEntryPoint(unauthenticated)
                .accessDeniedHandler(forbidden))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(unauthenticated)
                .accessDeniedHandler(forbidden))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .addFilterAfter(new RateLimitFilter(limit.capacity(), limit.refillPerSecond(), limit.maxCallers(), System::nanoTime, problems),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * RS256 tokens signed by the realm's keys (fetched from the JWKS endpoint with bounded timeouts, trusting the
     * configured SSL bundle when one is named), validated by {@link TokenValidation}.
     */
    @Bean
    JwtDecoder jwtDecoder(ExceptionApiProperties properties, ObjectProvider<SslBundles> sslBundles) {
        ExceptionApiProperties.Security security = properties.security();
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2));
        if (!security.sslBundle().isBlank()) {
            client.sslContext(sslBundles.getObject().getBundle(security.sslBundle()).createSslContext());
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client.build());
        requests.setReadTimeout(Duration.ofSeconds(3));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(requests))
            .build();
        decoder.setJwtValidator(TokenValidation.validator(security.issuer(), security.audience()));
        return decoder;
    }
}
