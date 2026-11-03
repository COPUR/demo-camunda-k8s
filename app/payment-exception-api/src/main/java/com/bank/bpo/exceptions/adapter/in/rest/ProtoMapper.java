package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.DecisionReceipt;
import com.bank.bpo.exceptions.contract.v1.CaseState;
import com.bank.bpo.exceptions.contract.v1.Decision;
import com.bank.bpo.exceptions.contract.v1.ErrorCategory;
import com.bank.bpo.exceptions.contract.v1.ErrorDefinition;
import com.bank.bpo.exceptions.contract.v1.ExceptionCase;
import com.bank.bpo.exceptions.contract.v1.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.contract.v1.ExceptionCatalog;
import com.bank.bpo.exceptions.contract.v1.ExceptionReason;
import com.bank.bpo.exceptions.contract.v1.ListExceptionCasesResponse;
import com.bank.bpo.exceptions.contract.v1.Outcome;
import com.bank.bpo.exceptions.contract.v1.Rail;
import com.bank.bpo.exceptions.contract.v1.RaiseExceptionCaseRequest;
import com.bank.bpo.exceptions.contract.v1.ReviewLevel;
import com.bank.bpo.exceptions.contract.v1.ReviewTask;
import com.bank.bpo.exceptions.contract.v1.SchemeStatus;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.google.protobuf.Timestamp;
import java.time.Instant;

/**
 * Translates between the domain and the published language (bpo.exceptions.v1). Every enum is mapped by an
 * exhaustive switch, so a new domain value does not compile until the contract says how it travels.
 */
final class ProtoMapper {

    private ProtoMapper() {
    }

    // ---- requests to the domain

    static NewExceptionCase toDomain(RaiseExceptionCaseRequest request) {
        return new NewExceptionCase(PaymentId.of(request.getPaymentId()), Jurisdiction.of(request.getJurisdiction()), toDomain(request.getRail()));
    }

    static com.bank.bpo.exceptions.domain.shared.Rail toDomain(Rail rail) {
        if (rail == Rail.RAIL_FAST) {
            return com.bank.bpo.exceptions.domain.shared.Rail.FAST;
        }
        throw new DomainException(ErrorCode.UNSUPPORTED_RAIL, "rail must be RAIL_FAST");
    }

    static com.bank.bpo.exceptions.domain.casemanagement.Decision toDomain(Decision decision) {
        return switch (decision) {
            case DECISION_SETTLED -> com.bank.bpo.exceptions.domain.casemanagement.Decision.SETTLED;
            case DECISION_REJECTED -> com.bank.bpo.exceptions.domain.casemanagement.Decision.REJECTED;
            case DECISION_ESCALATE -> com.bank.bpo.exceptions.domain.casemanagement.Decision.ESCALATE;
            case DECISION_EXPIRED -> com.bank.bpo.exceptions.domain.casemanagement.Decision.EXPIRED;
            case DECISION_UNSPECIFIED, UNRECOGNIZED ->
                throw new DomainException(ErrorCode.INVALID_DECISION, "decision must be DECISION_SETTLED, DECISION_REJECTED or DECISION_ESCALATE");
        };
    }

    static com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel toDomain(ReviewLevel level) {
        return switch (level) {
            case REVIEW_LEVEL_OPERATIONS -> com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel.OPERATIONS;
            case REVIEW_LEVEL_SUPERVISOR -> com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel.SUPERVISOR;
            case REVIEW_LEVEL_UNSPECIFIED, UNRECOGNIZED ->
                throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "reviewLevel must be REVIEW_LEVEL_OPERATIONS or REVIEW_LEVEL_SUPERVISOR");
        };
    }

    // ---- the domain to responses

    static ExceptionCase toProto(com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase source) {
        ExceptionCase.Builder out = ExceptionCase.newBuilder()
            .setPaymentId(source.paymentId().value())
            .setJurisdiction(source.jurisdiction().code())
            .setRail(toProto(source.rail()))
            .setState(toProto(source.state()))
            .setIncident(source.incident())
            .setRaisedAt(timestamp(source.raisedAt()))
            .setWorkflowInstanceKey(source.workflowInstanceKey())
            .setEtag(ETags.of(source));
        source.schemeStatus().ifPresent(status -> out.setSchemeStatus(toProto(status)));
        source.reason().ifPresent(reason -> out.setReason(toProto(reason)));
        source.openReview().ifPresent(task -> out.setOpenReview(toProto(task)));
        source.decision().ifPresent(decision -> out.setDecision(toProto(decision)));
        source.outcome().ifPresent(outcome -> out.setOutcome(toProto(outcome)));
        return out.build();
    }

    static com.bank.bpo.exceptions.contract.v1.DecisionReceipt toProto(DecisionReceipt receipt) {
        return com.bank.bpo.exceptions.contract.v1.DecisionReceipt.newBuilder()
            .setPaymentId(receipt.paymentId().value())
            .setReviewTaskId(receipt.reviewTaskId().value())
            .setLevel(toProto(receipt.level()))
            .setDecision(toProto(receipt.decision()))
            .setSubmittedAt(timestamp(receipt.submittedAt()))
            .build();
    }

    static ListExceptionCasesResponse toProto(CasePage page) {
        ListExceptionCasesResponse.Builder out = ListExceptionCasesResponse.newBuilder();
        page.items().forEach(item -> out.addExceptionCases(toProto(item)));
        page.nextPageToken().ifPresent(out::setNextPageToken);
        return out.build();
    }

    static ExceptionCatalog toProto(com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog catalog) {
        ExceptionCatalog.Builder out = ExceptionCatalog.newBuilder().setVersion(catalog.version());
        catalog.exceptionCases().forEach(entry -> out.addExceptionCases(toProto(entry)));
        catalog.errors().forEach(entry -> out.addErrors(toProto(entry)));
        return out.build();
    }

    static ExceptionCaseDefinition toProto(com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition entry) {
        return ExceptionCaseDefinition.newBuilder()
            .setCode(entry.code()).setName(entry.name()).setTitle(entry.title()).setSubDomain(entry.subDomain()).setTrigger(entry.trigger())
            .addAllBpmnElements(entry.bpmnElements()).setRouteTo(entry.routeTo()).addAllAllowedDecisions(entry.allowedDecisions())
            .setSlaSource(entry.slaSource()).setOwner(entry.owner()).addAllRelatedErrors(entry.relatedErrors()).setRunbook(entry.runbook())
            .build();
    }

    static ErrorDefinition toProto(com.bank.bpo.exceptions.domain.catalog.ErrorDefinition entry) {
        return ErrorDefinition.newBuilder()
            .setCode(entry.code()).setName(entry.name()).setTitle(entry.title()).setCategory(toProto(entry.category()))
            .setHttpStatus(entry.httpStatus()).setRetryable(entry.retryable()).setSubDomain(entry.subDomain())
            .setDescription(entry.description()).setRemediation(entry.remediation())
            .build();
    }

    private static ReviewTask toProto(com.bank.bpo.exceptions.domain.casemanagement.ReviewTask task) {
        ReviewTask.Builder out = ReviewTask.newBuilder().setTaskId(task.id().value()).setLevel(toProto(task.level()));
        task.queue().ifPresent(queue -> out.setQueue(queue.value()));
        task.dueAt().ifPresent(due -> out.setDueAt(timestamp(due)));
        task.assignee().ifPresent(out::setAssignee);
        task.allowedDecisions().forEach(decision -> out.addAllowedDecisions(toProto(decision)));
        return out.build();
    }

    static Rail toProto(com.bank.bpo.exceptions.domain.shared.Rail rail) {
        return switch (rail) {
            case FAST -> Rail.RAIL_FAST;
        };
    }

    static CaseState toProto(com.bank.bpo.exceptions.domain.casemanagement.CaseState state) {
        return switch (state) {
            case AWAITING_SCHEME_STATUS -> CaseState.CASE_STATE_AWAITING_SCHEME_STATUS;
            case IN_REVIEW -> CaseState.CASE_STATE_IN_REVIEW;
            case RESOLVING -> CaseState.CASE_STATE_RESOLVING;
            case SETTLED -> CaseState.CASE_STATE_SETTLED;
            case REJECTED -> CaseState.CASE_STATE_REJECTED;
            case HANDED_BACK -> CaseState.CASE_STATE_HANDED_BACK;
            case CANCELED -> CaseState.CASE_STATE_CANCELED;
        };
    }

    static SchemeStatus toProto(com.bank.bpo.exceptions.domain.casemanagement.SchemeStatus status) {
        return switch (status) {
            case ACSC -> SchemeStatus.SCHEME_STATUS_ACSC;
            case RJCT -> SchemeStatus.SCHEME_STATUS_RJCT;
            case PDNG -> SchemeStatus.SCHEME_STATUS_PDNG;
        };
    }

    static ExceptionReason toProto(com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason reason) {
        return switch (reason) {
            case SCHEME_STATUS_TIMEOUT -> ExceptionReason.EXCEPTION_REASON_SCHEME_STATUS_TIMEOUT;
            case SCHEME_STATUS_UNCLEAR -> ExceptionReason.EXCEPTION_REASON_SCHEME_STATUS_UNCLEAR;
            case OPERATIONS_ESCALATION -> ExceptionReason.EXCEPTION_REASON_OPERATIONS_ESCALATION;
            case OPERATIONS_SLA_EXPIRED -> ExceptionReason.EXCEPTION_REASON_OPERATIONS_SLA_EXPIRED;
            case SUPERVISOR_SLA_EXPIRED -> ExceptionReason.EXCEPTION_REASON_SUPERVISOR_SLA_EXPIRED;
        };
    }

    static ReviewLevel toProto(com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel level) {
        return switch (level) {
            case OPERATIONS -> ReviewLevel.REVIEW_LEVEL_OPERATIONS;
            case SUPERVISOR -> ReviewLevel.REVIEW_LEVEL_SUPERVISOR;
        };
    }

    static Decision toProto(com.bank.bpo.exceptions.domain.casemanagement.Decision decision) {
        return switch (decision) {
            case SETTLED -> Decision.DECISION_SETTLED;
            case REJECTED -> Decision.DECISION_REJECTED;
            case ESCALATE -> Decision.DECISION_ESCALATE;
            case EXPIRED -> Decision.DECISION_EXPIRED;
        };
    }

    static Outcome toProto(com.bank.bpo.exceptions.domain.casemanagement.Outcome outcome) {
        return switch (outcome) {
            case SETTLE -> Outcome.OUTCOME_SETTLE;
            case RELEASE -> Outcome.OUTCOME_RELEASE;
            case HANDED_BACK -> Outcome.OUTCOME_HANDED_BACK;
        };
    }

    static ErrorCategory toProto(com.bank.bpo.exceptions.domain.shared.ErrorCategory category) {
        return switch (category) {
            case VALIDATION -> ErrorCategory.ERROR_CATEGORY_VALIDATION;
            case AUTHENTICATION -> ErrorCategory.ERROR_CATEGORY_AUTHENTICATION;
            case AUTHORIZATION -> ErrorCategory.ERROR_CATEGORY_AUTHORIZATION;
            case NOT_FOUND -> ErrorCategory.ERROR_CATEGORY_NOT_FOUND;
            case CONFLICT -> ErrorCategory.ERROR_CATEGORY_CONFLICT;
            case PRECONDITION -> ErrorCategory.ERROR_CATEGORY_PRECONDITION;
            case BUSINESS_RULE -> ErrorCategory.ERROR_CATEGORY_BUSINESS_RULE;
            case LIMIT -> ErrorCategory.ERROR_CATEGORY_LIMIT;
            case DEPENDENCY -> ErrorCategory.ERROR_CATEGORY_DEPENDENCY;
            case INTERNAL -> ErrorCategory.ERROR_CATEGORY_INTERNAL;
        };
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
