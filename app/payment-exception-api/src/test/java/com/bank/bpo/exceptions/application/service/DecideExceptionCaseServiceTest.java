package com.bank.bpo.exceptions.application.service;

import static com.bank.bpo.exceptions.application.service.RaiseExceptionCaseServiceTest.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import com.bank.bpo.exceptions.adapter.out.idempotency.InMemoryIdempotencyStore;
import com.bank.bpo.exceptions.application.port.in.DecideCommand;
import com.bank.bpo.exceptions.application.port.in.DecisionResult;
import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTask;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.casemanagement.Reviewer;
import com.bank.bpo.exceptions.domain.casemanagement.SchemeStatus;
import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecideExceptionCaseServiceTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:05:00Z");
    static final PaymentId PAYMENT = PaymentId.of("demo-tr-unres-001");
    static final QueueName OPS = QueueName.of("pay-tr-fast-ops");
    static final Reviewer OPS1 = new Reviewer(new Actor("ops1"), Set.of(OPS));
    static final ReviewTaskId TASK = new ReviewTaskId(2251799813685400L);

    FakeCases fake;
    DecideExceptionCaseService service;

    @BeforeEach
    void setUp() {
        fake = new FakeCases();
        fake.cases.put(PAYMENT, new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.IN_REVIEW,
            Optional.of(SchemeStatus.PDNG), Optional.of(ExceptionReason.SCHEME_STATUS_UNCLEAR),
            Optional.of(new ReviewTask(TASK, ReviewLevel.OPERATIONS, Optional.of(OPS), Optional.empty(), Optional.empty())),
            Optional.empty(), Optional.empty(), false, NOW.minusSeconds(600), 2251799813685300L));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new DecideExceptionCaseService(fake, fake, fake, new InMemoryIdempotencyStore(Duration.ofHours(1), 100, clock), clock);
    }

    static DecideCommand command(Decision decision, String token, String key) {
        return new DecideCommand(PAYMENT, decision, OPS1, token, InteractionId.of("1b4e28ba-2fa1-11d2-883f-0016d3cca427"),
            IdempotencyKey.of(key));
    }

    @Test
    void aDecisionCompletesTheReviewAndIsPublished() {
        DecisionResult result = service.decide(command(Decision.ESCALATE, TASK.token(), "decide-0001"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.receipt().paymentId()).isEqualTo(PAYMENT);
        assertThat(result.receipt().decision()).isEqualTo(Decision.ESCALATE);
        assertThat(result.receipt().level()).isEqualTo(ReviewLevel.OPERATIONS);
        assertThat(result.receipt().reviewTaskId()).isEqualTo(TASK);
        assertThat(result.receipt().submittedAt()).isEqualTo(NOW);
        assertThat(fake.submitted).singleElement().satisfies(s -> assertThat(s.decision()).isEqualTo(Decision.ESCALATE));
        assertThat(fake.published).singleElement().isInstanceOf(ExceptionDecisionSubmitted.class);
    }

    @Test
    void aRetriedDecisionReplaysItsReceipt() {
        DecisionResult first = service.decide(command(Decision.SETTLED, TASK.token(), "decide-0001"));
        DecisionResult again = service.decide(command(Decision.SETTLED, TASK.token(), "decide-0001"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.receipt()).isEqualTo(first.receipt());
        assertThat(fake.submitted).hasSize(1);
    }

    @Test
    void anUnknownCaseIsNotFound() {
        DecideCommand unknown = new DecideCommand(PaymentId.of("demo-tr-none"), Decision.SETTLED, OPS1, TASK.token(),
            InteractionId.random(), IdempotencyKey.of("decide-0002"));
        assertRefused(() -> service.decide(unknown), ErrorCode.EXCEPTION_CASE_NOT_FOUND);
    }

    @Test
    void theReviewRulesOfTheAggregateApply() {
        assertRefused(() -> service.decide(command(Decision.SETTLED, "rt-1", "decide-0003")), ErrorCode.REVIEW_TASK_CHANGED);
        assertThat(fake.submitted).isEmpty();
    }

    @Test
    void aFailedSubmissionFreesTheKey() {
        fake.failSubmit = new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, "engine down");
        assertRefused(() -> service.decide(command(Decision.SETTLED, TASK.token(), "decide-0004")), ErrorCode.WORKFLOW_UNAVAILABLE);
        assertThat(fake.published).isEmpty();

        fake.failSubmit = null;
        assertThat(service.decide(command(Decision.SETTLED, TASK.token(), "decide-0004")).replayed()).isFalse();
    }
}
