package com.bank.bpo.exceptions.adapter.out.camunda;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.application.port.out.CaseWorkflow;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.Outcome;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTask;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.casemanagement.SchemeStatus;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.filter.VariableValueFilter;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The engine is the system of record: a case is the pay-instant-exception instance of its payment, read through the
 * engine's search API and changed through its commands. Nothing about a case is stored here, so there is no second
 * copy to drift (no dual write); the price is that the search API follows the engine with a short lag, which
 * {@link RecentlyOpened} covers for this replica's own writes.
 */
public final class CamundaExceptionCases implements ExceptionCaseRepository, CaseWorkflow {

    static final String OPS_REVIEW = "Task_OpsReview";
    static final String SUPERVISOR_REVIEW = "Task_SupervisorReview";
    static final String AWAIT_SCHEME_STATUS = "Task_AwaitSchemeStatus";
    static final List<String> CASE_VARIABLES = List.of("paymentId", "jurisdiction", "rail", "schemeStatus", "exceptionDecision", "exceptionOutcome");
    private static final int RECENT_MAX = 10_000;

    private final CamundaClient client;
    private final String processId;
    private final Duration timeout;
    private final RecentlyOpened recent;

    public CamundaExceptionCases(CamundaClient client, String processId, Duration timeout, Duration readYourWritesWindow, Clock clock) {
        this.client = client;
        this.processId = processId;
        this.timeout = timeout;
        this.recent = new RecentlyOpened(readYourWritesWindow, RECENT_MAX, clock);
    }

    // ---- commands

    /**
     * Starts the process with the variables the routing decision reads; the payment id is also the instance's
     * business id, so the engine can refuse a second instance for the payment when it enforces business id uniqueness.
     */
    @Override
    public long open(NewExceptionCase request) {
        Map<String, Object> variables = Map.of(
            "paymentId", request.paymentId().value(),
            "jurisdiction", request.jurisdiction().code(),
            "rail", request.rail().name());
        ProcessInstanceEvent created = EngineCalls.call("start the exception case", () -> client.newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .variables(variables)
            .businessId(request.paymentId().value())
            .requestTimeout(timeout)
            .send()
            .join(), Map.of(409, ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS));
        recent.remember(request, created.getProcessInstanceKey());
        return created.getProcessInstanceKey();
    }

    /** Completes the review task with the decision; the task's output mapping carries it to the process. */
    @Override
    public void submit(ExceptionDecisionSubmitted decision) {
        EngineCalls.call("complete the review task", () -> client.newCompleteUserTaskCommand(decision.reviewTaskId().value())
            .variables(Map.of("exceptionDecision", decision.decision().name()))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of(404, ErrorCode.REVIEW_TASK_CHANGED, 409, ErrorCode.REVIEW_TASK_CHANGED));
    }

    // ---- queries

    @Override
    public Optional<ExceptionCase> findByPaymentId(PaymentId paymentId) {
        List<Consumer<VariableValueFilter>> byPayment = List.of(value -> value.name("paymentId").value(jsonString(paymentId.value())));
        List<ProcessInstance> instances = EngineCalls.call("find the case", () -> client.newProcessInstanceSearchRequest()
            .filter(filter -> filter.processDefinitionId(processId).variables(byPayment))
            .sort(sort -> sort.startDate().desc())
            .page(page -> page.limit(1))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of()).items();
        if (instances.isEmpty()) {
            return recent.find(paymentId).map(CamundaExceptionCases::justOpened);
        }
        ProcessInstance instance = instances.getFirst();
        long key = instance.getProcessInstanceKey();
        Map<String, String> variables = variables(List.of(key)).getOrDefault(key, Map.of());
        return Optional.of(switch (instance.getState()) {
            case ACTIVE, SUSPENDED -> active(instance, variables);
            case COMPLETED -> project(instance, variables, completed(variables), Optional.empty());
            case TERMINATED -> project(instance, variables, CaseState.CANCELED, Optional.empty());
            case UNKNOWN_ENUM_VALUE -> project(instance, variables, CaseState.RESOLVING, Optional.empty());
        });
    }

    /** The work queue: open review tasks of the process, oldest first, with the cases they belong to. */
    @Override
    public CasePage findInReview(CaseQuery query) {
        Optional<String> after = query.pageToken().map(PageTokens::cursor);
        SearchResponse<UserTask> tasks = EngineCalls.call("list the review tasks", () -> client.newUserTaskSearchRequest()
            .filter(filter -> {
                filter.bpmnProcessId(processId).state(UserTaskState.CREATED);
                query.queue().ifPresent(queue -> filter.candidateGroup(queue.value()));
                query.level().ifPresent(level -> filter.elementId(elementOf(level)));
            })
            .sort(sort -> sort.creationDate().asc())
            .page(page -> {
                page.limit(query.pageSize());
                after.ifPresent(page::after);
            })
            .requestTimeout(timeout)
            .send()
            .join(), Map.of());
        List<Long> keys = tasks.items().stream().map(UserTask::getProcessInstanceKey).distinct().toList();
        if (keys.isEmpty()) {
            return new CasePage(List.of(), Optional.empty());
        }
        Map<Long, ProcessInstance> instances = EngineCalls.call("read the cases", () -> client.newProcessInstanceSearchRequest()
            .filter(filter -> filter.processInstanceKey(key -> key.in(keys)))
            .page(page -> page.limit(keys.size()))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of()).items().stream().collect(Collectors.toMap(ProcessInstance::getProcessInstanceKey, Function.identity(), (a, b) -> a));
        Map<Long, Map<String, String>> variables = variables(keys);
        List<ExceptionCase> items = tasks.items().stream()
            .filter(task -> instances.containsKey(task.getProcessInstanceKey()))
            .map(task -> project(instances.get(task.getProcessInstanceKey()),
                variables.getOrDefault(task.getProcessInstanceKey(), Map.of()), CaseState.IN_REVIEW, Optional.of(reviewTask(task))))
            .toList();
        String endCursor = tasks.page() == null ? null : tasks.page().endCursor();
        Optional<String> next = tasks.items().size() == query.pageSize() && endCursor != null && !endCursor.isBlank()
            ? Optional.of(PageTokens.token(endCursor)) : Optional.empty();
        return new CasePage(items, next);
    }

    // ---- projection of engine state onto the case

    private ExceptionCase active(ProcessInstance instance, Map<String, String> variables) {
        long key = instance.getProcessInstanceKey();
        List<UserTask> open = EngineCalls.call("read the review task", () -> client.newUserTaskSearchRequest()
            .filter(filter -> filter.processInstanceKey(key).state(UserTaskState.CREATED))
            .page(page -> page.limit(1))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of()).items();
        if (!open.isEmpty()) {
            return project(instance, variables, CaseState.IN_REVIEW, Optional.of(reviewTask(open.getFirst())));
        }
        boolean awaiting = !EngineCalls.call("read the case's position", () -> client.newElementInstanceSearchRequest()
            .filter(filter -> filter.processInstanceKey(key).elementId(AWAIT_SCHEME_STATUS).state(ElementInstanceState.ACTIVE))
            .page(page -> page.limit(1))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of()).items().isEmpty();
        return project(instance, variables, awaiting ? CaseState.AWAITING_SCHEME_STATUS : CaseState.RESOLVING, Optional.empty());
    }

    /** A completed instance ended settled, rejected or handed back: pay's outcome says which, else the scheme's answer. */
    static CaseState completed(Map<String, String> variables) {
        Optional<Outcome> outcome = Outcome.of(variables.get("exceptionOutcome"));
        if (outcome.isPresent()) {
            return switch (outcome.get()) {
                case SETTLE -> CaseState.SETTLED;
                case RELEASE -> CaseState.REJECTED;
                case HANDED_BACK -> CaseState.HANDED_BACK;
            };
        }
        return switch (SchemeStatus.of(variables.get("schemeStatus")).orElse(SchemeStatus.PDNG)) {
            case ACSC -> CaseState.SETTLED;
            case RJCT -> CaseState.REJECTED;
            case PDNG -> CaseState.HANDED_BACK;
        };
    }

    /**
     * Why people are (or were) looking at the case, from what the process recorded: no scheme status means the
     * inquiry timed out; any other answer than ACSC or RJCT (PDNG, or a code this context does not model, which
     * Gateway_SchemeStatus also sends to operations) that it stayed unclear; at supervisor level, an ESCALATE decision
     * means operations escalated, none that their SLA expired; EXPIRED that the supervisor's did too.
     */
    static Optional<ExceptionReason> reason(CaseState state, Optional<ReviewTask> review, Optional<String> reportedStatus,
                                            Optional<Decision> decision) {
        if (decision.isPresent() && decision.get() == Decision.EXPIRED) {
            return Optional.of(ExceptionReason.SUPERVISOR_SLA_EXPIRED);
        }
        if (review.isPresent() && review.get().level() == ReviewLevel.SUPERVISOR) {
            return Optional.of(decision.isPresent() && decision.get() == Decision.ESCALATE
                ? ExceptionReason.OPERATIONS_ESCALATION : ExceptionReason.OPERATIONS_SLA_EXPIRED);
        }
        if (decision.isPresent() && decision.get() == Decision.ESCALATE) {
            return Optional.of(ExceptionReason.OPERATIONS_ESCALATION);
        }
        if (state == CaseState.AWAITING_SCHEME_STATUS) {
            return Optional.empty();
        }
        if (reportedStatus.isEmpty()) {
            return Optional.of(ExceptionReason.SCHEME_STATUS_TIMEOUT);
        }
        boolean finalAnswer = SchemeStatus.of(reportedStatus.get()).filter(status -> status != SchemeStatus.PDNG).isPresent();
        return finalAnswer ? Optional.empty() : Optional.of(ExceptionReason.SCHEME_STATUS_UNCLEAR);
    }

    private ExceptionCase project(ProcessInstance instance, Map<String, String> variables, CaseState state, Optional<ReviewTask> review) {
        try {
            String paymentId = Optional.ofNullable(variables.get("paymentId")).orElse(instance.getBusinessId());
            Optional<SchemeStatus> schemeStatus = SchemeStatus.of(variables.get("schemeStatus"));
            Optional<Decision> decision = decision(variables.get("exceptionDecision"));
            Optional<Outcome> outcome = state.terminal() ? Outcome.of(variables.get("exceptionOutcome")) : Optional.empty();
            Instant raisedAt = Optional.ofNullable(instance.getStartDate()).map(OffsetDateTime::toInstant).orElse(Instant.EPOCH);
            return new ExceptionCase(PaymentId.of(paymentId), Jurisdiction.of(variables.get("jurisdiction")), Rail.of(variables.get("rail")), state,
                schemeStatus, reason(state, review, Optional.ofNullable(variables.get("schemeStatus")), decision), review, decision, outcome,
                Boolean.TRUE.equals(instance.getHasIncident()), raisedAt, instance.getProcessInstanceKey());
        } catch (DomainException | IllegalArgumentException | NullPointerException e) {
            throw new DomainException(ErrorCode.WORKFLOW_REJECTED,
                "process instance " + instance.getProcessInstanceKey() + " does not hold a case this API can read", e);
        }
    }

    private static ExceptionCase justOpened(RecentlyOpened.Entry entry) {
        NewExceptionCase request = entry.request();
        return new ExceptionCase(request.paymentId(), request.jurisdiction(), request.rail(), CaseState.AWAITING_SCHEME_STATUS,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, entry.openedAt(),
            entry.workflowInstanceKey());
    }

    static ReviewTask reviewTask(UserTask task) {
        ReviewLevel level = SUPERVISOR_REVIEW.equals(task.getElementId()) ? ReviewLevel.SUPERVISOR : ReviewLevel.OPERATIONS;
        Optional<QueueName> queue = Optional.ofNullable(task.getCandidateGroups()).orElse(List.of()).stream()
            .map(QueueName::parse).flatMap(Optional::stream).findFirst();
        return new ReviewTask(new ReviewTaskId(task.getUserTaskKey()), level, queue,
            Optional.ofNullable(task.getDueDate()).map(OffsetDateTime::toInstant),
            Optional.ofNullable(task.getAssignee()).filter(assignee -> !assignee.isBlank()));
    }

    /** The case's process variables (process scope only: a call activity's local copies are not the case's). */
    private Map<Long, Map<String, String>> variables(List<Long> keys) {
        List<Variable> found = EngineCalls.call("read the case variables", () -> client.newVariableSearchRequest()
            .filter(filter -> filter.processInstanceKey(key -> key.in(keys)).name(name -> name.in(CASE_VARIABLES)))
            .page(page -> page.limit(keys.size() * CASE_VARIABLES.size() * 4))
            .requestTimeout(timeout)
            .send()
            .join(), Map.of()).items();
        Map<Long, Map<String, String>> byInstance = new HashMap<>();
        for (Variable variable : found) {
            if (!Objects.equals(variable.getScopeKey(), variable.getProcessInstanceKey())) {
                continue;
            }
            stringValue(variable).ifPresent(value ->
                byInstance.computeIfAbsent(variable.getProcessInstanceKey(), k -> new HashMap<>()).put(variable.getName(), value));
        }
        return byInstance;
    }

    private static Optional<String> stringValue(Variable variable) {
        try {
            return Optional.ofNullable(variable.getValueAsType(String.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Decision> decision(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(Decision.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String elementOf(ReviewLevel level) {
        return switch (level) {
            case OPERATIONS -> OPS_REVIEW;
            case SUPERVISOR -> SUPERVISOR_REVIEW;
        };
    }

    /** A variable filter compares JSON values: a string variable is matched by its JSON form. */
    static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
