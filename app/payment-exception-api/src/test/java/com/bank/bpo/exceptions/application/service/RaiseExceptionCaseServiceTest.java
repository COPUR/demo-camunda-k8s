package com.bank.bpo.exceptions.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.adapter.out.idempotency.InMemoryIdempotencyStore;
import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.application.port.in.RaiseCommand;
import com.bank.bpo.exceptions.application.port.in.RaiseResult;
import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCaseRaised;
import com.bank.bpo.exceptions.domain.casemanagement.JurisdictionPolicy;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RaiseExceptionCaseServiceTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final PaymentId PAYMENT = PaymentId.of("demo-tr-unres-001");
    static final Actor PAY = new Actor("svc-pay");

    FakeCases fake;
    InMemoryIdempotencyStore idempotency;
    RaiseExceptionCaseService service;

    @BeforeEach
    void setUp() {
        fake = new FakeCases();
        idempotency = new InMemoryIdempotencyStore(Duration.ofHours(24), 1000, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new RaiseExceptionCaseService(new JurisdictionPolicy(Set.of(Jurisdiction.of("TR"))), fake, fake, fake, idempotency,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static RaiseCommand command(String key) {
        return new RaiseCommand(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST), PAY,
            InteractionId.of("1b4e28ba-2fa1-11d2-883f-0016d3cca427"), IdempotencyKey.of(key));
    }

    @Test
    void raisingOpensTheProcessAndPublishesTheFact() {
        RaiseResult result = service.raise(command("key-0001"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.exceptionCase().state()).isEqualTo(CaseState.AWAITING_SCHEME_STATUS);
        assertThat(result.exceptionCase().raisedAt()).isEqualTo(NOW);
        assertThat(fake.opened).containsExactly(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST));
        assertThat(fake.published).singleElement().isInstanceOfSatisfying(ExceptionCaseRaised.class, e -> {
            assertThat(e.paymentId()).isEqualTo(PAYMENT);
            assertThat(e.workflowInstanceKey()).isEqualTo(result.exceptionCase().workflowInstanceKey());
            assertThat(e.actor()).isEqualTo(PAY);
        });
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheFirstAnswerWithoutASecondProcess() {
        RaiseResult first = service.raise(command("key-0001"));
        RaiseResult again = service.raise(command("key-0001"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.exceptionCase()).isEqualTo(first.exceptionCase());
        assertThat(fake.opened).hasSize(1);
        assertThat(fake.published).hasSize(1);
    }

    @Test
    void theSameKeyForAnotherRequestIsRefused() {
        service.raise(command("key-0001"));
        RaiseCommand other = new RaiseCommand(new NewExceptionCase(PaymentId.of("demo-tr-unres-002"), Jurisdiction.of("TR"), Rail.FAST),
            PAY, InteractionId.random(), IdempotencyKey.of("key-0001"));

        assertRefused(() -> service.raise(other), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aPaymentGetsOneCaseEvenUnderADifferentKey() {
        service.raise(command("key-0001"));

        assertRefused(() -> service.raise(command("key-0002")), ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS);
        assertThat(fake.opened).hasSize(1);
    }

    @Test
    void aJurisdictionThisDeploymentDoesNotServeIsRefusedBeforeAnythingHappens() {
        RaiseCommand german = new RaiseCommand(new NewExceptionCase(PAYMENT, Jurisdiction.of("DE"), Rail.FAST), PAY,
            InteractionId.random(), IdempotencyKey.of("key-0003"));

        assertRefused(() -> service.raise(german), ErrorCode.JURISDICTION_NOT_SERVED);
        assertThat(fake.opened).isEmpty();
    }

    @Test
    void aFailedOpenFreesTheKeySoTheCallerCanRetry() {
        fake.failOpen = new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, "engine down");
        assertRefused(() -> service.raise(command("key-0001")), ErrorCode.WORKFLOW_UNAVAILABLE);
        assertThat(fake.published).isEmpty();

        fake.failOpen = null;
        assertThat(service.raise(command("key-0001")).replayed()).isFalse();
    }

    @Test
    void concurrentRaisesForOnePaymentOpenOneProcess() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String key = "key-concurrent-" + i;
            calls.add(pool.submit(() -> {
                start.await();
                return service.raise(command(key));
            }));
        }
        start.countDown();
        int refused = 0;
        for (Future<?> call : calls) {
            try {
                call.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOfSatisfying(DomainException.class,
                    d -> assertThat(d.code()).isEqualTo(ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS));
                refused++;
            }
        }
        pool.shutdownNow();
        assertThat(fake.opened).hasSize(1);
        assertThat(refused).isEqualTo(callers - 1);
    }

    static void assertRefused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
