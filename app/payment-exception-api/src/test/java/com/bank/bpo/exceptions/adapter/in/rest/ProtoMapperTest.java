package com.bank.bpo.exceptions.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.casemanagement.Outcome;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.SchemeStatus;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every value object has a contract value and back; the mapping is a total function on the domain's values. */
class ProtoMapperTest {

    @ParameterizedTest
    @EnumSource(CaseState.class)
    void everyCaseStateHasAContractValue(CaseState state) {
        assertThat(ProtoMapper.toProto(state).name()).isEqualTo("CASE_STATE_" + state.name());
    }

    @ParameterizedTest
    @EnumSource(SchemeStatus.class)
    void everySchemeStatusHasAContractValue(SchemeStatus status) {
        assertThat(ProtoMapper.toProto(status).name()).isEqualTo("SCHEME_STATUS_" + status.name());
    }

    @ParameterizedTest
    @EnumSource(ExceptionReason.class)
    void everyReasonHasAContractValue(ExceptionReason reason) {
        assertThat(ProtoMapper.toProto(reason).name()).isEqualTo("EXCEPTION_REASON_" + reason.name());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void everyOutcomeHasAContractValue(Outcome outcome) {
        assertThat(ProtoMapper.toProto(outcome).name()).isEqualTo("OUTCOME_" + outcome.name());
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void everyDecisionRoundTrips(Decision decision) {
        assertThat(ProtoMapper.toDomain(ProtoMapper.toProto(decision))).isEqualTo(decision);
    }

    @ParameterizedTest
    @EnumSource(ReviewLevel.class)
    void everyReviewLevelRoundTrips(ReviewLevel level) {
        assertThat(ProtoMapper.toDomain(ProtoMapper.toProto(level))).isEqualTo(level);
    }

    @ParameterizedTest
    @EnumSource(ErrorCategory.class)
    void everyErrorCategoryHasAContractValue(ErrorCategory category) {
        assertThat(ProtoMapper.toProto(category).name()).isEqualTo("ERROR_CATEGORY_" + category.name());
    }

    @Test
    void theContractsZeroValuesAreRefused() {
        assertThatThrownBy(() -> ProtoMapper.toDomain(com.bank.bpo.exceptions.contract.v1.Rail.RAIL_UNSPECIFIED))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_RAIL));
        assertThatThrownBy(() -> ProtoMapper.toDomain(com.bank.bpo.exceptions.contract.v1.Decision.UNRECOGNIZED))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DECISION));
        assertThat(ProtoMapper.toProto(Rail.FAST)).isEqualTo(com.bank.bpo.exceptions.contract.v1.Rail.RAIL_FAST);
    }

    @Test
    void aSettledCaseShowsItsDecisionOutcomeAndSchemeStatus() {
        ExceptionCase settled = new ExceptionCase(PaymentId.of("demo-tr-unres-401"), Jurisdiction.of("TR"), Rail.FAST, CaseState.SETTLED,
            Optional.of(SchemeStatus.PDNG), Optional.of(ExceptionReason.SCHEME_STATUS_UNCLEAR), Optional.empty(), Optional.of(Decision.SETTLED),
            Optional.of(Outcome.SETTLE), true, Instant.parse("2026-11-02T10:00:00Z"), 2251799813685300L);

        com.bank.bpo.exceptions.contract.v1.ExceptionCase contract = ProtoMapper.toProto(settled);

        assertThat(contract.getState()).isEqualTo(com.bank.bpo.exceptions.contract.v1.CaseState.CASE_STATE_SETTLED);
        assertThat(contract.getSchemeStatus()).isEqualTo(com.bank.bpo.exceptions.contract.v1.SchemeStatus.SCHEME_STATUS_PDNG);
        assertThat(contract.getDecision()).isEqualTo(com.bank.bpo.exceptions.contract.v1.Decision.DECISION_SETTLED);
        assertThat(contract.getOutcome()).isEqualTo(com.bank.bpo.exceptions.contract.v1.Outcome.OUTCOME_SETTLE);
        assertThat(contract.getIncident()).isTrue();
        assertThat(contract.hasOpenReview()).isFalse();
        assertThat(contract.getRaisedAt().getSeconds()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z").getEpochSecond());
        assertThat(contract.getEtag()).isEqualTo("\"settled\"");
    }
}
