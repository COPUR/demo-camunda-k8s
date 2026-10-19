package com.bank.bpo.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaymentsDemoLauncherTest {

    @TempDir
    Path dir;

    @Test
    void theShippedSampleDataIsSynthetic() throws Exception {
        assertThat(PaymentsDemoLauncher.readScenarios(Path.of("../../sample-data/scenarios.json"))).isNotEmpty();
    }

    @Test
    void aPaymentIdThatIsNotSyntheticIsRefused() throws Exception {
        Path file = Files.writeString(dir.resolve("s.json"), "{\"TR330006100519786457841326\": [\"ACSC\"]}");

        assertThatThrownBy(() -> PaymentsDemoLauncher.readScenarios(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScenarioWithoutStatusesIsRefused() throws Exception {
        Path file = Files.writeString(dir.resolve("s.json"), "{\"demo-x\": []}");

        assertThatThrownBy(() -> PaymentsDemoLauncher.readScenarios(file)).isInstanceOf(IllegalArgumentException.class);
    }
}
