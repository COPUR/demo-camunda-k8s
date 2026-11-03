package com.bank.bpo.exceptions.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the service reads from its environment (twelve-factor III), with safe defaults where one exists and a
 * refusal to start where none does: the token issuer and its keys have no default.
 */
@ConfigurationProperties("bpo.exceptions")
public record ExceptionApiProperties(
    @DefaultValue("TR") Set<String> servedJurisdictions,
    @DefaultValue Workflow workflow,
    @DefaultValue Idempotency idempotency,
    @DefaultValue Http http,
    @DefaultValue Security security,
    @DefaultValue Events events) {

    /** The process the cases run in, and how long one engine call may take. */
    public record Workflow(
        @DefaultValue("pay-instant-exception") String processId,
        @DefaultValue("PT5S") Duration requestTimeout,
        @DefaultValue("PT2M") Duration readYourWritesWindow) {

        public Workflow {
            requireText(processId, "bpo.exceptions.workflow.process-id");
            requirePositive(requestTimeout, "bpo.exceptions.workflow.request-timeout");
            requirePositive(readYourWritesWindow, "bpo.exceptions.workflow.read-your-writes-window");
        }
    }

    /** How long an answer is kept for a retry, and how many answers at most (memory is bounded). */
    public record Idempotency(@DefaultValue("PT24H") Duration ttl, @DefaultValue("10000") int maxEntries) {

        public Idempotency {
            requirePositive(ttl, "bpo.exceptions.idempotency.ttl");
            if (maxEntries < 1) {
                throw new IllegalArgumentException("bpo.exceptions.idempotency.max-entries is at least 1");
            }
        }
    }

    public record Http(@DefaultValue("16384") long maxRequestBytes, @DefaultValue RateLimit rateLimit) {

        public Http {
            if (maxRequestBytes < 1) {
                throw new IllegalArgumentException("bpo.exceptions.http.max-request-bytes is at least 1");
            }
        }
    }

    /** Per caller: a burst of {@code capacity} requests, then {@code refillPerSecond}. */
    public record RateLimit(
        @DefaultValue("30") int capacity,
        @DefaultValue("10") double refillPerSecond,
        @DefaultValue("10000") int maxCallers) {
    }

    /**
     * The token issuer (the Keycloak realm), where its keys are, and which audience this API answers to. The
     * principal claim names the actor; the groups claim lists the reviewer's queues.
     */
    public record Security(
        String issuer,
        String jwkSetUri,
        @DefaultValue("payment-exception-api") String audience,
        @DefaultValue("preferred_username") String principalClaim,
        @DefaultValue("groups") String groupsClaim,
        String sslBundle) {

        public Security {
            requireText(issuer, "bpo.exceptions.security.issuer");
            requireText(jwkSetUri, "bpo.exceptions.security.jwk-set-uri");
            requireText(audience, "bpo.exceptions.security.audience");
            requireText(principalClaim, "bpo.exceptions.security.principal-claim");
            requireText(groupsClaim, "bpo.exceptions.security.groups-claim");
            sslBundle = sslBundle == null ? "" : sslBundle.trim();
        }
    }

    /** Where domain events go: Kafka when bootstrap servers are set, the log otherwise. */
    public record Events(
        @DefaultValue("bpo.payment-exception.events.v1") String topic,
        @DefaultValue Kafka kafka,
        @DefaultValue Outbox outbox) {

        public Events {
            requireText(topic, "bpo.exceptions.events.topic");
        }
    }

    public record Kafka(String bootstrapServers, Map<String, String> properties) {

        public Kafka {
            bootstrapServers = bootstrapServers == null ? "" : bootstrapServers.trim();
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        public boolean enabled() {
            return !bootstrapServers.isEmpty();
        }
    }

    /** The in-process outbox between the request and the broker: bounded, retried with capped backoff. */
    public record Outbox(
        @DefaultValue("1000") int capacity,
        @DefaultValue("PT0.2S") Duration initialBackoff,
        @DefaultValue("PT30S") Duration maxBackoff) {

        public Outbox {
            if (capacity < 1) {
                throw new IllegalArgumentException("bpo.exceptions.events.outbox.capacity is at least 1");
            }
            requirePositive(initialBackoff, "bpo.exceptions.events.outbox.initial-backoff");
            requirePositive(maxBackoff, "bpo.exceptions.events.outbox.max-backoff");
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " is a positive duration");
        }
    }
}
