package com.bank.bpo.exceptions.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The service refuses to start on configuration it cannot run with, and names the property. */
class ExceptionApiPropertiesTest {

    static ExceptionApiProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("bpo.exceptions", ExceptionApiProperties.class);
    }

    static Map<String, String> minimal() {
        return Map.of("bpo.exceptions.security.issuer", "https://keycloak.test/auth/realms/camunda-platform",
            "bpo.exceptions.security.jwk-set-uri", "https://keycloak.test/auth/realms/camunda-platform/protocol/openid-connect/certs");
    }

    @Test
    void theDefaultsAreSafeForTheDemo() {
        ExceptionApiProperties properties = bind(minimal());

        assertThat(properties.servedJurisdictions()).containsExactly("TR");
        assertThat(properties.workflow().processId()).isEqualTo("pay-instant-exception");
        assertThat(properties.workflow().requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.idempotency().ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.http().maxRequestBytes()).isEqualTo(16384);
        assertThat(properties.http().rateLimit().capacity()).isEqualTo(30);
        assertThat(properties.security().audience()).isEqualTo("payment-exception-api");
        assertThat(properties.security().sslBundle()).isEmpty();
        assertThat(properties.events().kafka().enabled()).isFalse();
        assertThat(properties.events().kafka().properties()).isEmpty();
        assertThat(properties.events().outbox().capacity()).isEqualTo(1000);
    }

    @Test
    void kafkaIsOnWhenBootstrapServersAreSet() {
        Map<String, String> values = new java.util.HashMap<>(minimal());
        values.put("bpo.exceptions.events.kafka.bootstrap-servers", " redpanda:9092 ");
        values.put("bpo.exceptions.events.kafka.properties.security.protocol", "SSL");
        ExceptionApiProperties.Kafka kafka = bind(values).events().kafka();
        assertThat(kafka.enabled()).isTrue();
        assertThat(kafka.bootstrapServers()).isEqualTo("redpanda:9092");
        assertThat(kafka.properties()).containsEntry("security.protocol", "SSL");
    }

    @Test
    void theIssuerAndItsKeysHaveNoDefault() {
        assertThatThrownBy(() -> bind(Map.of())).isInstanceOf(BindException.class)
            .rootCause().hasMessageContaining("bpo.exceptions.security.issuer is required");
        assertThatThrownBy(() -> bind(Map.of("bpo.exceptions.security.issuer", "https://keycloak.test"))).isInstanceOf(BindException.class)
            .rootCause().hasMessageContaining("jwk-set-uri is required");
    }

    @Test
    void refusesLimitsAndDurationsThatCannotWork() {
        assertThatThrownBy(() -> new ExceptionApiProperties.Workflow("p", Duration.ZERO, Duration.ofMinutes(1)))
            .hasMessageContaining("request-timeout");
        assertThatThrownBy(() -> new ExceptionApiProperties.Workflow(" ", Duration.ofSeconds(1), Duration.ofMinutes(1)))
            .hasMessageContaining("process-id");
        assertThatThrownBy(() -> new ExceptionApiProperties.Workflow("p", Duration.ofSeconds(1), Duration.ofSeconds(-1)))
            .hasMessageContaining("read-your-writes-window");
        assertThatThrownBy(() -> new ExceptionApiProperties.Idempotency(null, 1)).hasMessageContaining("ttl");
        assertThatThrownBy(() -> new ExceptionApiProperties.Idempotency(Duration.ofHours(1), 0)).hasMessageContaining("max-entries");
        assertThatThrownBy(() -> new ExceptionApiProperties.Http(0, new ExceptionApiProperties.RateLimit(1, 1, 1)))
            .hasMessageContaining("max-request-bytes");
        assertThatThrownBy(() -> new ExceptionApiProperties.Events(" ", new ExceptionApiProperties.Kafka(null, null),
            new ExceptionApiProperties.Outbox(1, Duration.ofSeconds(1), Duration.ofSeconds(1)))).hasMessageContaining("topic");
        assertThatThrownBy(() -> new ExceptionApiProperties.Outbox(0, Duration.ofSeconds(1), Duration.ofSeconds(1))).hasMessageContaining("capacity");
        assertThatThrownBy(() -> new ExceptionApiProperties.Outbox(1, Duration.ZERO, Duration.ofSeconds(1))).hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new ExceptionApiProperties.Outbox(1, Duration.ofSeconds(1), Duration.ZERO)).hasMessageContaining("max-backoff");
        assertThatThrownBy(() -> new ExceptionApiProperties.Security("i", "j", "a", " ", "g", null)).hasMessageContaining("principal-claim");
        assertThatThrownBy(() -> new ExceptionApiProperties.Security("i", "j", "a", "p", null, null)).hasMessageContaining("groups-claim");
        assertThatThrownBy(() -> new ExceptionApiProperties.Security("i", "j", "", "p", "g", null)).hasMessageContaining("audience");
        assertThat(new ExceptionApiProperties.Security("i", "j", "a", "p", "g", " keycloak-ca ").sslBundle()).isEqualTo("keycloak-ca");
    }
}
