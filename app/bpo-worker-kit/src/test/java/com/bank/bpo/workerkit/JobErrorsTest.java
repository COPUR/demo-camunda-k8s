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
        })).isInstanceOf(ProviderException.class);
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
