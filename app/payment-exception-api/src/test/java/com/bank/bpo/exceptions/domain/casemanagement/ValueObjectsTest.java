package com.bank.bpo.exceptions.domain.casemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void theOperationsLevelMayEscalateTheSupervisorLevelMayNot() {
        assertThat(ReviewLevel.OPERATIONS.allowedDecisions()).containsExactly(Decision.SETTLED, Decision.REJECTED, Decision.ESCALATE);
        assertThat(ReviewLevel.SUPERVISOR.allowedDecisions()).containsExactly(Decision.SETTLED, Decision.REJECTED);
        assertThat(ReviewLevel.SUPERVISOR.allows(Decision.ESCALATE)).isFalse();
        assertThat(ReviewLevel.OPERATIONS.allows(Decision.EXPIRED)).isFalse();
    }

    @Test
    void expiredIsSetByTheSlaTimerNeverByAPerson() {
        assertThat(Decision.EXPIRED.byPerson()).isFalse();
        assertThat(Arrays.stream(Decision.values()).filter(Decision::byPerson))
            .containsExactly(Decision.SETTLED, Decision.REJECTED, Decision.ESCALATE);
        assertThat(Decision.of("SETTLED")).isEqualTo(Decision.SETTLED);
        for (String raw : Arrays.asList(null, "", "settled", "MAYBE")) {
            assertThatThrownBy(() -> Decision.of(raw))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DECISION));
        }
    }

    @Test
    void terminalStatesAreTheOutcomesOfRecord() {
        assertThat(Arrays.stream(CaseState.values()).filter(CaseState::terminal))
            .containsExactly(CaseState.SETTLED, CaseState.REJECTED, CaseState.HANDED_BACK, CaseState.CANCELED);
    }

    @Test
    void schemeStatusesAreTheIso20022CodesTheSchemeAnswers() {
        assertThat(SchemeStatus.of("PDNG")).contains(SchemeStatus.PDNG);
        assertThat(SchemeStatus.of(null)).isEmpty();
        assertThat(SchemeStatus.of("ACCP")).isEmpty();
        assertThat(SchemeStatus.ACSC.description()).isNotBlank();
    }

    @Test
    void outcomesAreTheOnesPayAnswers() {
        assertThat(Outcome.of("HANDED_BACK")).contains(Outcome.HANDED_BACK);
        assertThat(Outcome.of("LOST")).isEmpty();
        assertThat(Outcome.of(null)).isEmpty();
    }

    @Test
    void everyExceptionReasonPointsAtItsCatalogEntry() {
        assertThat(Arrays.stream(ExceptionReason.values()).map(ExceptionReason::catalogCode))
            .doesNotHaveDuplicates().allMatch(code -> code.matches("EXC-\\d{3}"));
    }

    @Test
    void aQueueIsAKeycloakGroupName() {
        assertThat(QueueName.of("pay-tr-fast-ops").value()).isEqualTo("pay-tr-fast-ops");
        assertThat(QueueName.of("pay-tr-fast-ops")).hasToString("pay-tr-fast-ops");
        for (String raw : Arrays.asList(null, "", "Pay-Ops", "-ops", "a", "ops queue", "x".repeat(64))) {
            assertThatThrownBy(() -> QueueName.of(raw))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_QUEUE));
        }
        assertThat(QueueName.parse("/pay-tr-fast-ops")).contains(QueueName.of("pay-tr-fast-ops"));
        assertThat(QueueName.parse("/bpo/pay-ops-l2")).contains(QueueName.of("pay-ops-l2"));
        assertThat(QueueName.parse("not a queue")).isEmpty();
        assertThat(QueueName.parse(null)).isEmpty();
    }

    @Test
    void aReviewTaskIdIsAPositiveEngineKeyAndItsTokenChangesWithIt() {
        assertThat(new ReviewTaskId(42).value()).isEqualTo(42);
        assertThat(new ReviewTaskId(42).token()).isEqualTo("rt-42");
        assertThatThrownBy(() -> new ReviewTaskId(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReviewTaskOffersTheDecisionsOfItsLevel() {
        ReviewTask task = new ReviewTask(new ReviewTaskId(7), ReviewLevel.SUPERVISOR, Optional.of(QueueName.of("pay-tr-fast-supervisor")),
            Optional.of(Instant.parse("2026-11-02T12:00:00Z")), Optional.empty());
        assertThat(task.allowedDecisions()).containsExactly(Decision.SETTLED, Decision.REJECTED);
        assertThatThrownBy(() -> new ReviewTask(null, ReviewLevel.OPERATIONS, Optional.empty(), Optional.empty(), Optional.empty()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aReviewerDecidesOnlyForTheQueuesTheyBelongTo() {
        Reviewer reviewer = new Reviewer(new Actor("ops1"), Set.of(QueueName.of("pay-tr-fast-ops")));
        assertThat(reviewer.memberOf(Optional.of(QueueName.of("pay-tr-fast-ops")))).isTrue();
        assertThat(reviewer.memberOf(Optional.of(QueueName.of("pay-tr-fast-supervisor")))).isFalse();
        assertThat(reviewer.memberOf(Optional.empty())).isFalse();
        assertThat(reviewer.subject()).isEqualTo("ops1");
    }

    @Test
    void aDeploymentRaisesCasesOnlyForTheJurisdictionsItServes() {
        JurisdictionPolicy policy = new JurisdictionPolicy(Set.of(Jurisdiction.of("TR")));
        policy.requireServed(Jurisdiction.of("TR"));
        assertThatThrownBy(() -> policy.requireServed(Jurisdiction.of("DE")))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JURISDICTION_NOT_SERVED));
        assertThatThrownBy(() -> new JurisdictionPolicy(Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.served()).containsExactly(Jurisdiction.of("TR"));
    }
}
