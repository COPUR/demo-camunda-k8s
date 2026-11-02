package com.bank.bpo.exceptions.config;

import java.util.Map;

/**
 * The demo's start-up guard, the same as the payments demo's: the service starts only where the environment says
 * that it holds synthetic data only (demo-tr-* payment ids), so the image cannot be run against real payments by
 * accident.
 */
public final class SyntheticDataGuard {

    public static final String ACK_VARIABLE = "BPO_DEMO_ACK";
    public static final String ACK_VALUE = "synthetic-data-only";

    private SyntheticDataGuard() {
    }

    public static void require(Map<String, String> environment) {
        if (!ACK_VALUE.equals(environment.get(ACK_VARIABLE))) {
            throw new IllegalStateException(ACK_VARIABLE + " must be " + ACK_VALUE + ": this service is for the local demo with synthetic data only");
        }
    }
}
