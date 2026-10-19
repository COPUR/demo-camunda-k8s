package com.bank.bpo.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.payments.ProviderConfiguration.WorkerTokens;
import com.bank.bpo.workerkit.ClientCredentialsToken;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The onboarding rules (DevSecOps B-1) hold for the payments family too: no fixed token and no plain http outside
 * the simulator. On top of that every provider gets its own token with its own scope, so no token carries more than
 * one provider's audience.
 */
class ProviderConfigurationTest {

    private static final String TOKEN_URI = "https://keycloak.bank/token";
    private static final String CLIENT = "svc-bpo-worker-payments";

    private static ProviderConfiguration deployed() {
        return new ProviderConfiguration(new MockEnvironment());
    }

    private static ProviderConfiguration simulator() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProviderConfiguration.SIMULATOR_PROFILE);
        return new ProviderConfiguration(environment);
    }

    @Test
    void aDeployedWorkerWithoutATokenEndpointDoesNotStart() {
        assertThatThrownBy(() -> deployed().workerTokens("", CLIENT, "x"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("token-uri is required");
    }

    @Test
    void aDeployedWorkerRefusesAPlainHttpTokenEndpoint() {
        assertThatThrownBy(() -> deployed().workerTokens("http://keycloak.bank/token", CLIENT, "x"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    @Test
    void aDeployedWorkerNeedsItsCredentialAndAScopeForEveryProvider() {
        assertThatThrownBy(() -> deployed().workerTokens(TOKEN_URI, CLIENT, "").scoped("acc", "aud-acc"))
            .hasMessageContaining("client credential");
        WorkerTokens tokens = deployed().workerTokens(TOKEN_URI, CLIENT, "x");
        assertThatThrownBy(() -> deployed().holdPort("https://acc.bank/", "", tokens)).hasMessageContaining("bpo.auth.scopes.acc");
        assertThatThrownBy(() -> deployed().schemeStatusPort("https://psg-tr.bank/", " ", tokens)).hasMessageContaining("bpo.auth.scopes.psg-tr");
        assertThatThrownBy(() -> deployed().paymentExceptionPort("https://pay.bank/", "", tokens)).hasMessageContaining("bpo.auth.scopes.pay");
        assertThatThrownBy(() -> deployed().notificationPort("https://ntf.bank/", "", tokens)).hasMessageContaining("bpo.auth.scopes.ntf");
    }

    @Test
    void eachProviderGetsItsOwnTokenForItsOwnScope() {
        WorkerTokens tokens = deployed().workerTokens(TOKEN_URI, CLIENT, "x");

        Supplier<String> acc = tokens.scoped("acc", "aud-acc");
        Supplier<String> pay = tokens.scoped("pay", "aud-pay");

        assertThat(acc).isInstanceOf(ClientCredentialsToken.class).isNotSameAs(pay);
        assertThat(pay).isInstanceOf(ClientCredentialsToken.class);
    }

    @Test
    void aDeployedWorkerRefusesAPlainHttpProvider() {
        WorkerTokens tokens = (provider, scope) -> () -> "t";
        assertThatThrownBy(() -> deployed().holdPort("http://acc.bank/", "aud-acc", tokens)).hasMessageContaining("https");
        assertThatThrownBy(() -> deployed().schemeStatusPort("http://psg-tr.bank/", "aud-psg", tokens)).hasMessageContaining("https");
        assertThat(deployed().holdPort("https://acc.bank/", "aud-acc", tokens)).isNotNull();
    }

    @Test
    void theSimulatorTokenOnlyTravelsToLoopback() {
        WorkerTokens tokens = simulator().workerTokens("", CLIENT, "");
        assertThat(tokens.scoped("pay", "").get()).isEqualTo("simulator-token");
        assertThatThrownBy(() -> simulator().paymentExceptionPort("http://pay.bank/", "", tokens)).hasMessageContaining("loopback");
        assertThatThrownBy(() -> simulator().paymentExceptionPort("https://pay.bank/", "", tokens)).hasMessageContaining("loopback");
        assertThat(simulator().paymentExceptionPort("http://127.0.0.1:8099/", "", tokens)).isNotNull();
    }
}
