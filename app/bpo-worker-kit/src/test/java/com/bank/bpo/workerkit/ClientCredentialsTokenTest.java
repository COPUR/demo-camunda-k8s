package com.bank.bpo.workerkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.bpo.simulator.DomainSimulator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClientCredentialsTokenTest {

    /** A clock the test moves. */
    private static final class Moving extends Clock {
        private Instant now = Instant.parse("2026-10-18T06:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @Test
    void theTokenIsReusedUntilShortlyBeforeItExpires() {
        try (DomainSimulator sim = DomainSimulator.start()) {
            sim.tokenLifetimeSeconds(300);
            Moving clock = new Moving();
            ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "svc-test", "not-a-real-credential", "aud-x", clock);

            assertThat(token.get()).isEqualTo("token-1");
            clock.advance(Duration.ofSeconds(200));
            assertThat(token.get()).isEqualTo("token-1");
            clock.advance(Duration.ofSeconds(80)); // 280 s: inside the 30 s margin before expiry
            assertThat(token.get()).isEqualTo("token-2");
            assertThat(sim.tokensIssued()).isEqualTo(2);
        }
    }

    @Test
    void theRequestNamesTheClientAndTheScope() {
        try (DomainSimulator sim = DomainSimulator.start()) {
            new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "svc-bpo-worker-onboarding", "not-a-real-credential", "aud-svc-acc", Clock.systemUTC()).get();
            assertThat(sim.callsTo("/token")).hasSize(1);
            assertThat(sim.callsTo("/token").get(0).body())
                .containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", "svc-bpo-worker-onboarding")
                .containsEntry("scope", "aud-svc-acc");
        }
    }

    @Test
    void anUnreachableTokenEndpointIsTransient() {
        ClientCredentialsToken token = new ClientCredentialsToken(URI.create("http://127.0.0.1:1/token"), "c", "x", "s", Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
            (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.TRANSIENT));
    }

    // ---- DevSecOps B-1 and S-10 ----

    @Test
    void theTokenEndpointMustBeHttpsAndTheCredentialAndScopeAreRequired() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientCredentialsToken(URI.create("http://keycloak.bank/token"), "c", "x", "s", Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientCredentialsToken(URI.create("https://keycloak.bank/token"), "c", "", "s", Clock.systemUTC()))
            .hasMessageContaining("client credential");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientCredentialsToken(URI.create("https://keycloak.bank/token"), "c", "x", " ", Clock.systemUTC()))
            .hasMessageContaining("scope");
    }

    @Test
    void aRefusedClientIsAContractFaultNotARetry() {
        try (DomainSimulator sim = DomainSimulator.start()) {
            for (int status : new int[] {400, 401, 403}) {
                sim.failWith("token", status, "unauthorized_client", 1);
                ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "s", Clock.systemUTC());
                org.assertj.core.api.Assertions.assertThatThrownBy(token::get).as("status " + status).isInstanceOfSatisfying(ProviderException.class,
                    (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.CONTRACT));
            }
            sim.failWith("token", 503, "unavailable", 1);
            ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "s", Clock.systemUTC());
            org.assertj.core.api.Assertions.assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
                (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.TRANSIENT));
        }
    }

    @Test
    void anAnswerWithoutATokenIsNotCached() {
        try (DomainSimulator sim = DomainSimulator.start()) {
            sim.answerRaw("token", 200, "{\"token_type\":\"Bearer\"}", 1);
            ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "s", Clock.systemUTC());
            org.assertj.core.api.Assertions.assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
                (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.CONTRACT));
            assertThat(token.get()).isEqualTo("token-1");
        }
    }

    // ---- T1: a token endpoint that accepts and never answers stalled every job type ----

    @Test
    void aSilentTokenEndpointFailsTransientlyWithinFiveSeconds() {
        try (StalledServer keycloak = StalledServer.start(StalledServer.Mode.SILENT)) {
            ClientCredentialsToken token = new ClientCredentialsToken(keycloak.uri("/token"), "c", "x", "s", Clock.systemUTC());
            long started = System.nanoTime();
            ProviderException failure = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(7),
                () -> org.assertj.core.api.Assertions.catchThrowableOfType(ProviderException.class, token::get));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(5_900));
            assertThat(failure.kind()).isEqualTo(ProviderException.Kind.TRANSIENT);
            assertThat(failure.code()).isEqualTo("TOKEN_TIMEOUT");
        }
    }

    @Test
    void aTokenAnswerThatStopsInTheMiddleOfTheBodyFailsToo() {
        try (StalledServer keycloak = StalledServer.start(StalledServer.Mode.HEADERS_ONLY)) {
            ClientCredentialsToken token = new ClientCredentialsToken(keycloak.uri("/token"), "c", "x", "s", Clock.systemUTC(),
                Duration.ofMillis(300), Duration.ofMillis(500));
            ProviderException failure = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> org.assertj.core.api.Assertions.catchThrowableOfType(ProviderException.class, token::get));
            assertThat(failure.kind()).isEqualTo(ProviderException.Kind.TRANSIENT);
            assertThat(failure.code()).isEqualTo("TOKEN_TIMEOUT");
        }
    }

    @Test
    void aTokenThatTimedOutIsFetchedAgainOnTheNextCall() {
        try (StalledServer keycloak = StalledServer.start(StalledServer.Mode.SILENT)) {
            ClientCredentialsToken token = new ClientCredentialsToken(keycloak.uri("/token"), "c", "x", "s", Clock.systemUTC(),
                Duration.ofMillis(300), Duration.ofMillis(300));
            for (int attempt = 0; attempt < 2; attempt++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
                    (e) -> assertThat(e.code()).isEqualTo("TOKEN_TIMEOUT"));
            }
        }
    }

    @Test
    void otherJobTypesProgressWhileOneProvidersTokenEndpointHangs() throws Exception {
        ExecutorService jobThreads = Executors.newFixedThreadPool(8); // camunda.client.execution-threads
        try (DomainSimulator sim = DomainSimulator.start(); StalledServer keycloak = StalledServer.start(StalledServer.Mode.SILENT)) {
            sim.kyc("a1", "PENDING");
            ClientCredentialsToken stuck = new ClientCredentialsToken(keycloak.uri("/token"), "c", "x", "aud-acc", Clock.systemUTC());
            ClientCredentialsToken healthy = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "aud-psg", Clock.systemUTC());
            ProviderHttp acc = ProviderHttp.loopback(sim.baseUrl(), stuck, Duration.ofSeconds(5));
            ProviderHttp psg = ProviderHttp.loopback(sim.baseUrl(), healthy, Duration.ofSeconds(5));

            Future<?> hung = jobThreads.submit(() -> acc.get("/api/v1/applications/a1/kyc"));
            List<Future<?>> others = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                others.add(jobThreads.submit(() -> psg.get("/api/v1/applications/a1/kyc")));
            }
            for (Future<?> other : others) {
                other.get(2, TimeUnit.SECONDS);
            }
            assertThat(hung.isDone()).as("the stalled call is still waiting while the others finished").isFalse();
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> hung.get(6, TimeUnit.SECONDS)))
                .hasCauseInstanceOf(ProviderException.class);
        } finally {
            jobThreads.shutdownNow();
        }
    }
}
