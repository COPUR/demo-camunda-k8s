package com.bank.bpo.workerkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.simulator.DomainSimulator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The refusals at the edges of the kit: wiring that must not start, and answers that must not be trusted. */
class KitGuardsTest {

    private static final Duration FIVE = Duration.ofSeconds(5);

    @Test
    void aProviderBaseUrlIsSchemeHostPortAndPathOnly() {
        for (String url : new String[] {"https://p.bank/?q=1", "https://p.bank/#f", "https://u@p.bank/"}) {
            assertThatThrownBy(() -> ProviderHttp.https(URI.create(url), () -> "t", FIVE)).as(url)
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ProviderHttp.https(URI.create("http://p.bank/"), () -> "t", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderHttp.https(URI.create("https:/p"), () -> "t", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderHttp.https(URI.create("https://p.bank/"), () -> "t", FIVE)).isNotNull();
    }

    @Test
    void theSimulatorIsPlainHttpOnLoopbackOnly() {
        assertThatThrownBy(() -> ProviderHttp.loopback(URI.create("https://127.0.0.1/"), () -> "t", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderHttp.loopback(URI.create("http://p.bank/"), () -> "t", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderHttp.loopback(URI.create("http://localhost:1/"), () -> "t", FIVE)).isNotNull();
        assertThat(ProviderHttp.loopback(URI.create("http://[::1]:1/"), () -> "t", FIVE)).isNotNull();
    }

    @Test
    void aPathThatIsNotAPlainResourcePathIsRefusedBeforeAnyCall() {
        ProviderHttp http = ProviderHttp.loopback(URI.create("http://127.0.0.1:1/"), () -> "t", FIVE);
        for (String path : new String[] {"x", "/a/../b", "/a%2f", "/a//b", "/a?b", "/a#b", "/a\\b"}) {
            assertThatThrownBy(() -> http.get(path)).as(path).isInstanceOfSatisfying(ProviderException.class,
                (e) -> assertThat(e.code()).isEqualTo("INVALID_PATH"));
        }
    }

    @Test
    void aTokenEndpointOnPlainHttpIsRefusedUnlessItIsLoopback() {
        assertThatThrownBy(() -> new ClientCredentialsToken(URI.create("ftp://keycloak.bank/token"), "c", "x", "s", Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientCredentialsToken(URI.create("https://keycloak.bank/token"), null, "x", "s", Clock.systemUTC()))
            .hasMessageContaining("client id");
        assertThat(new ClientCredentialsToken(URI.create("http://localhost:1/token"), "c", "x", "s", Clock.systemUTC())).isNotNull();
    }

    @Test
    void aTokenAnswerWithoutAUsableLifetimeIsNotTrusted() {
        try (DomainSimulator sim = DomainSimulator.start()) {
            for (String body : new String[] {"not json", "{\"access_token\":\" \",\"expires_in\":60}",
                "{\"access_token\":\"a\",\"expires_in\":\"60\"}", "{\"access_token\":\"a\",\"expires_in\":0}", "null"}) {
                sim.answerRaw("token", 200, body, 1);
                ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "s", Clock.systemUTC());
                assertThatThrownBy(token::get).as(body).isInstanceOfSatisfying(ProviderException.class,
                    (e) -> assertThat(e.code()).isEqualTo("TOKEN_UNREADABLE"));
            }
            sim.failWith("token", 429, "slow_down", 1);
            ClientCredentialsToken token = new ClientCredentialsToken(sim.baseUrl().resolve("/token"), "c", "x", "s", Clock.systemUTC());
            assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
                (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.TRANSIENT));
        }
    }

    @Test
    void anInterruptedTokenFetchKeepsTheInterruptFlag() {
        try (StalledServer keycloak = StalledServer.start(StalledServer.Mode.SILENT)) {
            ClientCredentialsToken token = new ClientCredentialsToken(keycloak.uri("/token"), "c", "x", "s", Clock.systemUTC());
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(token::get).isInstanceOfSatisfying(ProviderException.class,
                    (e) -> assertThat(e.code()).isEqualTo("INTERRUPTED"));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void anInterruptedProviderCallKeepsTheInterruptFlag() {
        try (StalledServer provider = StalledServer.start(StalledServer.Mode.SILENT)) {
            ProviderHttp http = ProviderHttp.loopback(provider.uri("/"), () -> "t", FIVE);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> http.get("/a")).isInstanceOfSatisfying(ProviderException.class,
                    (e) -> assertThat(e.code()).isEqualTo("INTERRUPTED"));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void theBackoffNeverExceedsTheCap() {
        assertThat(JobErrors.retryBackoff(0)).isEqualTo(Duration.ofSeconds(32));
        assertThat(JobErrors.retryBackoff(Integer.MIN_VALUE + 10)).isEqualTo(Duration.ofSeconds(60));
        assertThat(JobErrors.camel("__insufficient__funds")).isEqualTo("InsufficientFunds");
    }

    @Test
    void anInteractionInsideAnotherRestoresTheOuterOne() {
        String outer = Interaction.within("demo-tr-outer", () -> {
            String before = Interaction.current();
            Interaction.within("demo-tr-inner", () -> Interaction.current());
            assertThat(Interaction.current()).isEqualTo(before);
            return before;
        });
        assertThat(outer).isEqualTo(Interaction.idFor("demo-tr-outer"));
    }

    @Test
    void anEnumValueMustBeAStringFromTheList() {
        assertThatThrownBy(() -> Ids.oneOf("rail", 7, Set.of("FAST"))).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> Ids.oneOf("rail", "EFT", Set.of("FAST"))).isInstanceOf(ProviderException.class);
        assertThat(Ids.oneOf("rail", "FAST", Set.of("FAST"))).isEqualTo("FAST");
        assertThatThrownBy(() -> IdempotencyKeys.of("demo-tr-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutputVariables.of(Set.of("n"), Map.of("n", 7))).isInstanceOf(IllegalStateException.class);
    }
}
