package com.bank.bpo.demo;

import java.util.Map;

import com.bank.bpo.payments.PaymentsWorkerApplication;
import org.springframework.boot.SpringApplication;

/**
 * Entry point of the demo image. {@code BPO_DEMO_ROLE} picks what the container is:
 * <ul>
 *   <li>{@code all-in-one} (default): the single-pod demo, {@link PaymentsDemoLauncher};</li>
 *   <li>{@code worker}: the real payments workers on their deployed path (https providers, Keycloak
 *       client-credentials token), with no simulator in the JVM;</li>
 *   <li>{@code psg-tr}, {@code acc}, {@code pay}, {@code ntf}: one simulated provider, {@link ProviderSimulatorService}.</li>
 * </ul>
 * Every role refuses to start without the synthetic-data acknowledgement.
 */
public final class DemoMain {

    private DemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        if (!PaymentsDemoLauncher.ACK_VALUE.equals(env.get(PaymentsDemoLauncher.ACK_VARIABLE))) {
            throw new IllegalStateException(PaymentsDemoLauncher.ACK_VARIABLE + " must be " + PaymentsDemoLauncher.ACK_VALUE
                + ": this image is for the local demo only");
        }
        String role = env.getOrDefault("BPO_DEMO_ROLE", "all-in-one");
        switch (role) {
            case "all-in-one" -> PaymentsDemoLauncher.main(args);
            case "worker" -> SpringApplication.run(PaymentsWorkerApplication.class, args);
            default -> ProviderSimulatorService.run(ProviderRole.of(role), env);
        }
    }
}
