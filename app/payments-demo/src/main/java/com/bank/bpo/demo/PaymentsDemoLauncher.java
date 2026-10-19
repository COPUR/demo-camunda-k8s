package com.bank.bpo.demo;

import com.bank.bpo.payments.PaymentsWorkerApplication;
import com.bank.bpo.simulator.DomainSimulator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.springframework.boot.SpringApplication;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The local POC/POT demo: the real payments workers in the simulator profile, with the domain simulator in the same
 * JVM standing in for psg-tr, pay, ntf and the acc holds. The simulator listens on the loopback address only, so
 * nothing outside this process can reach it and the workers can reach nothing else. The scheme answers per payment
 * come from a scenario file of synthetic payment ids.
 */
public final class PaymentsDemoLauncher {

    static final String ACK_VARIABLE = "BPO_DEMO_ACK";
    static final String ACK_VALUE = "synthetic-data-only";
    private static final Logger LOG = Logger.getLogger(PaymentsDemoLauncher.class.getName());
    private static final TypeReference<Map<String, List<String>>> SCENARIOS = new TypeReference<>() { };

    private PaymentsDemoLauncher() {
    }

    public static void main(String[] args) throws IOException {
        if (!ACK_VALUE.equals(System.getenv(ACK_VARIABLE))) {
            throw new IllegalStateException(ACK_VARIABLE + " must be " + ACK_VALUE + ": this image runs a simulator and is for the local demo only");
        }
        Map<String, List<String>> scenarios = readScenarios(Path.of(System.getenv().getOrDefault("BPO_DEMO_SCENARIOS", "/config/scenarios.json")));
        DomainSimulator simulator = DomainSimulator.start();
        scenarios.forEach((paymentId, statuses) -> simulator.schemeStatuses(paymentId, statuses.toArray(String[]::new)));
        for (String provider : new String[] {"psg-tr", "acc", "pay", "ntf"}) {
            System.setProperty("bpo.providers." + provider + ".base-url", simulator.baseUrl().toString());
        }
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> LOG.info(summary(simulator)), 15, 15, TimeUnit.SECONDS);
        SpringApplication application = new SpringApplication(PaymentsWorkerApplication.class);
        application.setAdditionalProfiles("simulator");
        application.run(args);
    }

    static Map<String, List<String>> readScenarios(Path file) throws IOException {
        Map<String, List<String>> scenarios = JsonMapper.builder().build().readValue(Files.readString(file), SCENARIOS);
        scenarios.forEach((paymentId, statuses) -> {
            if (!paymentId.matches("demo-[a-z0-9-]{1,40}") || statuses.isEmpty()) {
                throw new IllegalArgumentException("scenario ids must be synthetic (demo-...) and name at least one status: " + paymentId);
            }
        });
        return scenarios;
    }

    static String summary(DomainSimulator simulator) {
        return "demo simulator: calls=" + simulator.calls().size() + " holdsCaptured=" + simulator.holdsCaptured();
    }
}
