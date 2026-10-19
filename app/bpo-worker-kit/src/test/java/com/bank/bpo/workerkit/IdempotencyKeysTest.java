package com.bank.bpo.workerkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void theKeyIsBusinessKeyAndStep() {
        assertThat(IdempotencyKeys.of("app-1", "open")).isEqualTo("app-1:open");
    }

    @Test
    void aLoopedMoneyStepCarriesItsPass() {
        assertThat(IdempotencyKeys.of("loan-9", "collect", 3)).isEqualTo("loan-9:collect:3");
    }

    @Test
    void theSameInputGivesTheSameKeyAfterARestart() {
        assertThat(IdempotencyKeys.of("app-1", "open")).isEqualTo(IdempotencyKeys.of("app-1", "open"));
    }

    @Test
    void aBusinessKeyThatIsNotAPlainIdIsRefused() {
        for (String bad : new String[] {"jane doe", null, "app:1", "..", "x/../y", "%2e%2e", "a.b", "x".repeat(65)}) {
            assertThatThrownBy(() -> IdempotencyKeys.of(bad, "open")).isInstanceOfSatisfying(ProviderException.class,
                (e) -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.CONTRACT));
        }
    }

    @Test
    void aStepThatIsNotKebabCaseIsRefused() {
        assertThatThrownBy(() -> IdempotencyKeys.of("app-1", "Open")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.of("app-1", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePassStartsAtOne() {
        assertThatThrownBy(() -> IdempotencyKeys.of("loan-9", "collect", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
