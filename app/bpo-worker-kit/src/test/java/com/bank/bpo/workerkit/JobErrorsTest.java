package com.bank.bpo.workerkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.client.exception.BpmnError;
import io.camunda.client.exception.JobError;
import org.junit.jupiter.api.Test;

class JobErrorsTest {

    private static ProviderException failure(ProviderException.Kind kind, String code) {
        return new ProviderException(kind, code, "provider answered " + code, null);
    }

    @Test
    void aSuccessIsReturned() {
        assertThat(JobErrors.guard(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void aBusinessRejectionBecomesABpmnErrorNamedAfterTheCode() {
        assertThatThrownBy(() -> JobErrors.guard(() -> {
            throw failure(ProviderException.Kind.BUSINESS_REJECTION, "INSUFFICIENT_FUNDS");
        })).isInstanceOfSatisfying(BpmnError.class, (e) -> assertThat(e.getErrorCode()).isEqualTo("Err_InsufficientFunds"));
    }

    @Test
    void aTransientFailureFailsTheJobSoTheEngineRetriesIt() {
        assertThatThrownBy(() -> JobErrors.guard(() -> {
            throw failure(ProviderException.Kind.TRANSIENT, "UNAVAILABLE");
        })).isInstanceOfSatisfying(JobError.class, (e) -> {
            assertThat(e.getRetries()).as("null: the engine's count minus one").isNull();
            assertThat(e.getErrorMessage()).contains("TRANSIENT").contains("UNAVAILABLE");
            assertThat(e.getCause()).isInstanceOf(ProviderException.class);
        });
    }

    @Test
    void theRetriesBackOffExponentiallyUpToACap() {
        JobError error = org.assertj.core.api.Assertions.catchThrowableOfType(JobError.class, () -> JobErrors.guard(() -> {
            throw failure(ProviderException.Kind.TRANSIENT, "TOKEN_TIMEOUT");
        }));
        // the model gives each task retries="5": 4 retries left after the first failure, then 3, 2, 1
        assertThat(error.getRetryBackoff().apply(4)).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(error.getRetryBackoff().apply(3)).isEqualTo(java.time.Duration.ofSeconds(4));
        assertThat(error.getRetryBackoff().apply(2)).isEqualTo(java.time.Duration.ofSeconds(8));
        assertThat(error.getRetryBackoff().apply(1)).isEqualTo(java.time.Duration.ofSeconds(16));
        assertThat(JobErrors.retryBackoff(-20)).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(JobErrors.retryBackoff(9)).as("more retries than the model gives").isEqualTo(java.time.Duration.ofSeconds(2));
    }

    @Test
    void aReusedKeyRaisesTheIncidentAtOnce() {
        assertThatThrownBy(() -> JobErrors.guard(() -> {
            throw failure(ProviderException.Kind.KEY_REUSED, "IDEMPOTENCY_KEY_REUSED");
        })).isInstanceOfSatisfying(JobError.class, (e) -> assertThat(e.getRetries()).isZero());
    }

    @Test
    void aWrongContractRaisesTheIncidentAtOnce() {
        assertThatThrownBy(() -> JobErrors.guard(() -> {
            throw failure(ProviderException.Kind.CONTRACT, "HTTP_404");
        })).isInstanceOfSatisfying(JobError.class, (e) -> assertThat(e.getRetries()).isZero());
    }
}
