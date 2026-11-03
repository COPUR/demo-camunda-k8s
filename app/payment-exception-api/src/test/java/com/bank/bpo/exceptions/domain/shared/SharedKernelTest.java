package com.bank.bpo.exceptions.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SharedKernelTest {

    @Test
    void aPaymentIdIsASyntheticDemoTrId() {
        assertThat(PaymentId.of("demo-tr-unres-001").value()).isEqualTo("demo-tr-unres-001");
        assertThat(PaymentId.of("demo-tr-unres-001")).hasToString("demo-tr-unres-001");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "TR000000000000000000000000", "demo-unres-001", "DEMO-TR-1", "demo-tr-", "demo-tr-../etc",
        "demo-tr-a b", "demo-tr-0123456789012345678901234567890123456789x"})
    void anythingElseIsRefusedSoNoRealPaymentDataEntersTheApi(String raw) {
        assertThatThrownBy(() -> PaymentId.of(raw))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PAYMENT_ID));
    }

    @Test
    void aJurisdictionIsAnIsoAlpha2Code() {
        assertThat(Jurisdiction.of("TR").code()).isEqualTo("TR");
        assertThat(Jurisdiction.of("TR")).hasToString("TR");
        for (String raw : Arrays.asList(null, "tr", "TUR", "T1", "")) {
            assertThatThrownBy(() -> Jurisdiction.of(raw))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JURISDICTION));
        }
    }

    @Test
    void onlyTheRailsThisContextKnowsAreAccepted() {
        assertThat(Rail.of("FAST")).isEqualTo(Rail.FAST);
        for (String raw : Arrays.asList(null, "fast", "SWIFT", "")) {
            assertThatThrownBy(() -> Rail.of(raw))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_RAIL));
        }
    }

    @Test
    void anActorIsAnOpaquePrintableSubject() {
        assertThat(new Actor("ops1").subject()).isEqualTo("ops1");
        for (String raw : Arrays.asList(null, "", " ", "a\nb", "x".repeat(256))) {
            assertThatThrownBy(() -> new Actor(raw))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        }
    }

    @Test
    void anInteractionIdIsAnRfc4122Uuid() {
        assertThat(InteractionId.of("1B4E28BA-2FA1-11D2-883F-0016D3CCA427").value()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThat(InteractionId.parse("not-a-uuid")).isEmpty();
        assertThat(InteractionId.parse(null)).isEmpty();
        assertThat(InteractionId.random().value()).matches("[0-9a-f-]{36}");
        assertThat(InteractionId.of("1b4e28ba-2fa1-11d2-883f-0016d3cca427")).hasToString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThatThrownBy(() -> InteractionId.of("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEventIdIsStableForTheSameBusinessFact() {
        UUID first = EventId.forFact("ExceptionCaseRaised", "demo-tr-unres-001");
        assertThat(EventId.forFact("ExceptionCaseRaised", "demo-tr-unres-001")).isEqualTo(first);
        assertThat(EventId.forFact("ExceptionCaseRaised", "demo-tr-unres-002")).isNotEqualTo(first);
        assertThat(EventId.forFact("ExceptionDecisionSubmitted", "demo-tr-unres-001")).isNotEqualTo(first);
        // "a|b" + "c" and "a" + "b|c" must not collide.
        assertThat(EventId.forFact("a|b", "c")).isNotEqualTo(EventId.forFact("a", "b|c"));
    }

    @Test
    void everyErrorCodeIsUniqueWellFormedAndCategorised() {
        assertThat(new HashSet<>(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList())).hasSize(ErrorCode.values().length);
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.code()).matches("PEX-[1-9][0-9]{3}");
            assertThat(code.title()).isNotBlank().doesNotEndWith(".");
            assertThat(code.category()).isNotNull();
        }
        assertThat(ErrorCode.fromCode("PEX-3001")).contains(ErrorCode.EXCEPTION_CASE_NOT_FOUND);
        assertThat(ErrorCode.fromCode("PEX-0000")).isEmpty();
        assertThat(ErrorCode.WORKFLOW_UNAVAILABLE.retryable()).isTrue();
        assertThat(ErrorCode.INVALID_PAYMENT_ID.retryable()).isFalse();
    }

    @Test
    void aDomainExceptionCarriesItsCodeAndASafeMessage() {
        DomainException e = new DomainException(ErrorCode.EXCEPTION_CASE_NOT_FOUND, "no case for demo-tr-x");
        assertThat(e.code()).isEqualTo(ErrorCode.EXCEPTION_CASE_NOT_FOUND);
        assertThat(e).hasMessage("no case for demo-tr-x");
        DomainException wrapped = new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, "engine down", new IllegalStateException("x"));
        assertThat(wrapped).hasCauseInstanceOf(IllegalStateException.class);
    }
}
