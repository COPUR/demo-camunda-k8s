package com.bank.bpo.exceptions.domain.casemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.EventId;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExceptionCaseTest {

    static final PaymentId PAYMENT = PaymentId.of("demo-tr-unres-001");
    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final InteractionId INTERACTION = InteractionId.of("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
    static final QueueName OPS = QueueName.of("pay-tr-fast-ops");
    static final QueueName SUPERVISORS = QueueName.of("pay-tr-fast-supervisor");
    static final Reviewer OPS_REVIEWER = new Reviewer(new Actor("ops1"), Set.of(OPS));
    static final Reviewer SUPERVISOR = new Reviewer(new Actor("sup1"), Set.of(SUPERVISORS));

    static ExceptionCase inReview(ReviewLevel level, QueueName queue, Optional<String> assignee, ExceptionReason reason) {
        return new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.IN_REVIEW, Optional.of(SchemeStatus.PDNG),
            Optional.of(reason), Optional.of(new ReviewTask(new ReviewTaskId(2251799813685400L), level, Optional.of(queue),
                Optional.of(NOW.plusSeconds(1800)), assignee)), Optional.empty(), Optional.empty(), false, NOW, 2251799813685300L);
    }

    @Test
    void openingACaseStartsItAwaitingTheSchemeStatusAndRaisesTheEvent() {
        ExceptionCase.Opened opened = ExceptionCase.open(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST), 99L,
            new Actor("svc-pay"), INTERACTION, NOW);

        assertThat(opened.exceptionCase().state()).isEqualTo(CaseState.AWAITING_SCHEME_STATUS);
        assertThat(opened.exceptionCase().openReview()).isEmpty();
        assertThat(opened.exceptionCase().workflowInstanceKey()).isEqualTo(99L);
        assertThat(opened.exceptionCase().raisedAt()).isEqualTo(NOW);
        assertThat(opened.exceptionCase().concurrencyToken()).isEqualTo("awaiting_scheme_status");
        ExceptionCaseRaised event = opened.event();
        assertThat(event.eventId()).isEqualTo(EventId.forFact("ExceptionCaseRaised", PAYMENT.value()));
        assertThat(event.paymentId()).isEqualTo(PAYMENT);
        assertThat(event.jurisdiction()).isEqualTo(Jurisdiction.of("TR"));
        assertThat(event.rail()).isEqualTo(Rail.FAST);
        assertThat(event.workflowInstanceKey()).isEqualTo(99L);
        assertThat(event.actor().subject()).isEqualTo("svc-pay");
        assertThat(event.interactionId()).isEqualTo(INTERACTION);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void anOperationsReviewerSettlesRejectsOrEscalates() {
        for (Decision decision : new Decision[] {Decision.SETTLED, Decision.REJECTED, Decision.ESCALATE}) {
            ExceptionCase c = inReview(ReviewLevel.OPERATIONS, OPS, Optional.empty(), ExceptionReason.SCHEME_STATUS_TIMEOUT);
            ExceptionDecisionSubmitted event = c.decide(decision, OPS_REVIEWER, c.concurrencyToken(), INTERACTION, NOW);

            assertThat(event.decision()).isEqualTo(decision);
            assertThat(event.level()).isEqualTo(ReviewLevel.OPERATIONS);
            assertThat(event.queue()).contains(OPS);
            assertThat(event.reviewTaskId()).isEqualTo(new ReviewTaskId(2251799813685400L));
            assertThat(event.workflowInstanceKey()).isEqualTo(2251799813685300L);
            assertThat(event.actor().subject()).isEqualTo("ops1");
            assertThat(event.eventId()).isEqualTo(EventId.forFact("ExceptionDecisionSubmitted", PAYMENT.value(), "2251799813685400"));
            assertThat(event.paymentId()).isEqualTo(PAYMENT);
            assertThat(event.interactionId()).isEqualTo(INTERACTION);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        }
    }

    @Test
    void aSupervisorCannotEscalateFurther() {
        ExceptionCase c = inReview(ReviewLevel.SUPERVISOR, SUPERVISORS, Optional.empty(), ExceptionReason.OPERATIONS_ESCALATION);
        assertThat(c.decide(Decision.SETTLED, SUPERVISOR, c.concurrencyToken(), INTERACTION, NOW).level()).isEqualTo(ReviewLevel.SUPERVISOR);
        assertRefused(() -> c.decide(Decision.ESCALATE, SUPERVISOR, c.concurrencyToken(), INTERACTION, NOW), ErrorCode.DECISION_NOT_ALLOWED);
    }

    @Test
    void nobodyRecordsAnExpiryByHand() {
        ExceptionCase c = inReview(ReviewLevel.OPERATIONS, OPS, Optional.empty(), ExceptionReason.SCHEME_STATUS_UNCLEAR);
        assertRefused(() -> c.decide(Decision.EXPIRED, OPS_REVIEWER, c.concurrencyToken(), INTERACTION, NOW), ErrorCode.DECISION_NOT_ALLOWED);
    }

    @Test
    void onlyAMemberOfTheRoutedQueueDecides() {
        ExceptionCase c = inReview(ReviewLevel.SUPERVISOR, SUPERVISORS, Optional.empty(), ExceptionReason.OPERATIONS_SLA_EXPIRED);
        assertRefused(() -> c.decide(Decision.SETTLED, OPS_REVIEWER, c.concurrencyToken(), INTERACTION, NOW), ErrorCode.NOT_A_QUEUE_MEMBER);
    }

    @Test
    void aTaskClaimedInTasklistBelongsToItsAssignee() {
        ExceptionCase claimedByOther = inReview(ReviewLevel.OPERATIONS, OPS, Optional.of("ops2"), ExceptionReason.SCHEME_STATUS_TIMEOUT);
        assertRefused(() -> claimedByOther.decide(Decision.SETTLED, OPS_REVIEWER, claimedByOther.concurrencyToken(), INTERACTION, NOW),
            ErrorCode.REVIEW_TASK_CLAIMED_BY_OTHER);

        ExceptionCase claimedBySelf = inReview(ReviewLevel.OPERATIONS, OPS, Optional.of("ops1"), ExceptionReason.SCHEME_STATUS_TIMEOUT);
        assertThat(claimedBySelf.decide(Decision.SETTLED, OPS_REVIEWER, claimedBySelf.concurrencyToken(), INTERACTION, NOW)).isNotNull();
    }

    @Test
    void aDecisionOnAStaleViewIsRefused() {
        ExceptionCase c = inReview(ReviewLevel.OPERATIONS, OPS, Optional.empty(), ExceptionReason.SCHEME_STATUS_TIMEOUT);
        assertThat(c.concurrencyToken()).isEqualTo("rt-2251799813685400");
        assertRefused(() -> c.decide(Decision.SETTLED, OPS_REVIEWER, "rt-1", INTERACTION, NOW), ErrorCode.REVIEW_TASK_CHANGED);
        assertRefused(() -> c.decide(Decision.SETTLED, OPS_REVIEWER, null, INTERACTION, NOW), ErrorCode.PRECONDITION_REQUIRED);
    }

    @Test
    void aCaseWithoutAnOpenReviewTakesNoDecision() {
        ExceptionCase awaiting = ExceptionCase.open(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST), 5L,
            new Actor("svc-pay"), INTERACTION, NOW).exceptionCase();
        assertRefused(() -> awaiting.decide(Decision.SETTLED, OPS_REVIEWER, awaiting.concurrencyToken(), INTERACTION, NOW),
            ErrorCode.NO_OPEN_REVIEW);
    }

    @Test
    void theStateAndTheOpenReviewAlwaysAgree() {
        ReviewTask task = new ReviewTask(new ReviewTaskId(1), ReviewLevel.OPERATIONS, Optional.of(OPS), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.IN_REVIEW, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, NOW, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.SETTLED, Optional.empty(),
            Optional.empty(), Optional.of(task), Optional.empty(), Optional.empty(), false, NOW, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.RESOLVING, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionCase(null, Jurisdiction.of("TR"), Rail.FAST, CaseState.RESOLVING, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, NOW, 1L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTerminalCaseCarriesItsStateAsToken() {
        ExceptionCase settled = new ExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST, CaseState.SETTLED, Optional.of(SchemeStatus.ACSC),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, NOW, 1L);
        assertThat(settled.concurrencyToken()).isEqualTo("settled");
        assertThat(settled.state().terminal()).isTrue();
    }

    private static void assertRefused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
