package com.bank.bpo.exceptions.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticDataGuardTest {

    @Test
    void startsOnlyWhereTheEnvironmentSaysTheDataIsSynthetic() {
        assertThatCode(() -> SyntheticDataGuard.require(Map.of("BPO_DEMO_ACK", "synthetic-data-only"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> SyntheticDataGuard.require(Map.of())).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BPO_DEMO_ACK must be synthetic-data-only");
        assertThatThrownBy(() -> SyntheticDataGuard.require(Map.of("BPO_DEMO_ACK", "yes"))).isInstanceOf(IllegalStateException.class);
    }
}
