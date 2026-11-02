package com.bank.bpo.exceptions.adapter.out.camunda;

import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.ELEMENT_INSTANCE_SEARCH;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.PROCESS_INSTANCES;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.PROCESS_INSTANCE_SEARCH;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.USER_TASK_SEARCH;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.VARIABLE_SEARCH;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.created;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.elementInstance;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.instance;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.page;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.problem;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.userTask;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.variable;
import static com.bank.bpo.exceptions.adapter.out.camunda.FakeEngine.variables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.Decision;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.Outcome;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.casemanagement.SchemeStatus;
import com.bank.bpo.exceptions.domain.shared.Actor;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import io.camunda.client.CamundaClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class CamundaExceptionCasesTest {

    static final long INSTANCE = 2251799813685300L;
    static final long TASK = 2251799813685400L;
    static final PaymentId PAYMENT = PaymentId.of("demo-tr-unres-501");

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    FakeEngine engine;
    CamundaClient client;
    CamundaExceptionCases cases;

    @BeforeEach
    void start() throws Exception {
        engine = new FakeEngine();
        client = engine.client();
        cases = new CamundaExceptionCases(client, "pay-instant-exception", Duration.ofSeconds(2), Duration.ofMinutes(2), clock);
    }

    @AfterEach
    void stop() {
        client.close();
        engine.close();
    }

    static JsonNode json(String body) {
        return FakeEngine.JSON.readTree(body);
    }

    static Map<String, String> vars(String... namesAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("paymentId", PAYMENT.value());
        values.put("jurisdiction", "TR");
        values.put("rail", "FAST");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return values;
    }

    void scriptInstance(String state, boolean incident, Map<String, String> values) {
        engine.answer(PROCESS_INSTANCE_SEARCH, 200, page(List.of(instance(INSTANCE, state, incident)), "c1"));
        engine.answer(VARIABLE_SEARCH, 200, page(variables(INSTANCE, values), "c1"));
    }

    @Test
    void opensACaseAsAProcessInstanceNamedByThePayment() {
        engine.answer(PROCESS_INSTANCES, 200, created(INSTANCE, PAYMENT.value()));

        long key = cases.open(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST));

        assertThat(key).isEqualTo(INSTANCE);
        JsonNode request = json(engine.calls(PROCESS_INSTANCES).getFirst().body());
        assertThat(request.get("processDefinitionId").asString()).isEqualTo("pay-instant-exception");
        assertThat(request.get("businessId").asString()).isEqualTo(PAYMENT.value());
        assertThat(request.get("variables").get("paymentId").asString()).isEqualTo(PAYMENT.value());
        assertThat(request.get("variables").get("jurisdiction").asString()).isEqualTo("TR");
        assertThat(request.get("variables").get("rail").asString()).isEqualTo("FAST");
    }

    @Test
    void readsItsOwnWriteWhileTheSearchHasNotCaughtUpAndForgetsItAfterTheWindow() {
        engine.answer(PROCESS_INSTANCES, 200, created(INSTANCE, PAYMENT.value()));
        engine.answer(PROCESS_INSTANCE_SEARCH, 200, page(List.of(), null));
        cases.open(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST));

        Optional<ExceptionCase> justOpened = cases.findByPaymentId(PAYMENT);
        assertThat(justOpened).hasValueSatisfying(c -> {
            assertThat(c.state()).isEqualTo(CaseState.AWAITING_SCHEME_STATUS);
            assertThat(c.workflowInstanceKey()).isEqualTo(INSTANCE);
            assertThat(c.raisedAt()).isEqualTo(now.get());
        });

        now.set(now.get().plus(Duration.ofMinutes(3)));
        assertThat(cases.findByPaymentId(PAYMENT)).isEmpty();
        assertThat(cases.findByPaymentId(PaymentId.of("demo-tr-unres-599"))).isEmpty();
    }

    @Test
    void findsTheLatestInstanceOfThePaymentByItsVariable() {
        scriptInstance("ACTIVE", false, vars());
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(userTask(TASK, INSTANCE, "Task_OpsReview", "pay-tr-fast-ops", null)), "c1"));

        cases.findByPaymentId(PAYMENT);

        JsonNode search = json(engine.calls(PROCESS_INSTANCE_SEARCH).getFirst().body());
        assertThat(search.get("filter").get("processDefinitionId").toString()).contains("pay-instant-exception");
        JsonNode byVariable = search.get("filter").get("variables").get(0);
        assertThat(byVariable.get("name").asString()).isEqualTo("paymentId");
        assertThat(byVariable.get("value").toString()).contains("\\\"" + PAYMENT.value() + "\\\"");
        assertThat(search.get("sort").get(0).get("field").asString()).isEqualTo("startDate");
        assertThat(search.get("sort").get(0).get("order").asString()).isEqualToIgnoringCase("desc");
    }

    @Test
    void anOpenOperationsTaskIsACaseInReviewWithItsQueueDueDateAndConcurrencyToken() {
        scriptInstance("ACTIVE", false, vars());
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(userTask(TASK, INSTANCE, "Task_OpsReview", "pay-tr-fast-ops", null)), "c1"));

        ExceptionCase found = cases.findByPaymentId(PAYMENT).orElseThrow();

        assertThat(found.state()).isEqualTo(CaseState.IN_REVIEW);
        assertThat(found.reason()).contains(ExceptionReason.SCHEME_STATUS_TIMEOUT);
        assertThat(found.openReview()).hasValueSatisfying(task -> {
            assertThat(task.id()).isEqualTo(new ReviewTaskId(TASK));
            assertThat(task.level()).isEqualTo(ReviewLevel.OPERATIONS);
            assertThat(task.queue()).contains(QueueName.of("pay-tr-fast-ops"));
            assertThat(task.dueAt()).contains(Instant.parse("2026-11-02T10:35:00Z"));
            assertThat(task.assignee()).isEmpty();
        });
        assertThat(found.concurrencyToken()).isEqualTo("rt-" + TASK);
        assertThat(found.raisedAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    }

    /**
     * Gateway_SchemeStatus sends every answer other than ACSC and RJCT to operations as unclear, including codes this
     * context does not model (found against the real engine: RCVD read as a timeout). Only no answer at all is one.
     */
    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "-,    SCHEME_STATUS_TIMEOUT",
        "PDNG, SCHEME_STATUS_UNCLEAR",
        "RCVD, SCHEME_STATUS_UNCLEAR",
        "ACCP, SCHEME_STATUS_UNCLEAR",
    })
    void anOperationsReviewIsUnclearUnlessTheSchemeNeverAnswered(String schemeStatus, ExceptionReason expected) {
        Map<String, String> values = vars();
        if (schemeStatus != null) {
            values.put("schemeStatus", schemeStatus);
        }
        scriptInstance("ACTIVE", false, values);
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(userTask(TASK, INSTANCE, "Task_OpsReview", "pay-tr-fast-ops", null)), "c1"));

        ExceptionCase found = cases.findByPaymentId(PAYMENT).orElseThrow();

        assertThat(found.reason()).contains(expected);
        assertThat(found.schemeStatus()).isEqualTo(Optional.ofNullable(schemeStatus).flatMap(SchemeStatus::of));
    }

    @Test
    void aSupervisorTaskShowsWhyOperationsHandedItOn() {
        scriptInstance("ACTIVE", true, vars("exceptionDecision", "ESCALATE"));
        engine.answer(USER_TASK_SEARCH, 200,
            page(List.of(userTask(TASK, INSTANCE, "Task_SupervisorReview", "pay-tr-fast-supervisor", "sup.mehmet")), "c1"));

        ExceptionCase escalated = cases.findByPaymentId(PAYMENT).orElseThrow();

        assertThat(escalated.reason()).contains(ExceptionReason.OPERATIONS_ESCALATION);
        assertThat(escalated.decision()).contains(Decision.ESCALATE);
        assertThat(escalated.incident()).isTrue();
        assertThat(escalated.openReview().orElseThrow().assignee()).contains("sup.mehmet");
    }

    @Test
    void anActiveCaseWithoutATaskWaitsForTheSchemeOrIsBeingResolved() {
        scriptInstance("ACTIVE", false, vars());
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(), null));
        engine.answer(ELEMENT_INSTANCE_SEARCH, 200, page(List.of(elementInstance(INSTANCE, "Task_AwaitSchemeStatus")), "c1"));
        engine.answer(ELEMENT_INSTANCE_SEARCH, 200, page(List.of(), null));

        ExceptionCase awaiting = cases.findByPaymentId(PAYMENT).orElseThrow();
        assertThat(awaiting.state()).isEqualTo(CaseState.AWAITING_SCHEME_STATUS);
        assertThat(awaiting.reason()).isEmpty();

        assertThat(cases.findByPaymentId(PAYMENT).orElseThrow().state()).isEqualTo(CaseState.RESOLVING);
        JsonNode elementFilter = json(engine.calls(ELEMENT_INSTANCE_SEARCH).getFirst().body()).get("filter");
        assertThat(elementFilter.get("elementId").toString()).contains("Task_AwaitSchemeStatus");
        assertThat(elementFilter.get("state").toString()).contains("ACTIVE");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "COMPLETED, SETTLE,      PDNG, SETTLED,  SETTLED,     SETTLE,      SCHEME_STATUS_UNCLEAR",
        "COMPLETED, RELEASE,     -,    REJECTED, REJECTED,    RELEASE,     SCHEME_STATUS_TIMEOUT",
        "COMPLETED, HANDED_BACK, -,    EXPIRED,  HANDED_BACK, HANDED_BACK, SUPERVISOR_SLA_EXPIRED",
        "COMPLETED, -,           ACSC, -,        SETTLED,     -,           -",
        "COMPLETED, -,           RJCT, -,        REJECTED,    -,           -",
        "COMPLETED, -,           -,    ESCALATE, HANDED_BACK, -,           OPERATIONS_ESCALATION",
        "TERMINATED, -,          -,    -,        CANCELED,    -,           SCHEME_STATUS_TIMEOUT",
    })
    void aFinishedInstanceEndsTheCaseTheWayTheProcessEnded(String instanceState, String outcome, String schemeStatus, String decision,
                                                           CaseState expectedState, Outcome expectedOutcome, ExceptionReason expectedReason) {
        Map<String, String> values = vars();
        if (outcome != null) {
            values.put("exceptionOutcome", outcome);
        }
        if (schemeStatus != null) {
            values.put("schemeStatus", schemeStatus);
        }
        if (decision != null) {
            values.put("exceptionDecision", decision);
        }
        scriptInstance(instanceState, false, values);

        ExceptionCase ended = cases.findByPaymentId(PAYMENT).orElseThrow();

        assertThat(ended.state()).isEqualTo(expectedState);
        assertThat(ended.outcome()).isEqualTo(Optional.ofNullable(expectedOutcome));
        assertThat(ended.reason()).isEqualTo(Optional.ofNullable(expectedReason));
        assertThat(ended.schemeStatus()).isEqualTo(Optional.ofNullable(schemeStatus).flatMap(SchemeStatus::of));
        assertThat(ended.openReview()).isEmpty();
    }

    @Test
    void ignoresCopiesOfVariablesInInnerScopesAndValuesItCannotRead() {
        engine.answer(PROCESS_INSTANCE_SEARCH, 200, page(List.of(instance(INSTANCE, "COMPLETED", false)), "c1"));
        List<Map<String, Object>> values = new java.util.ArrayList<>(variables(INSTANCE, vars("schemeStatus", "ACSC")));
        values.add(variable(INSTANCE, INSTANCE + 7, "schemeStatus", "RJCT"));
        Map<String, Object> unreadable = variable(INSTANCE, INSTANCE, "exceptionOutcome", "x");
        unreadable.put("value", "{not json");
        values.add(unreadable);
        engine.answer(VARIABLE_SEARCH, 200, page(values, "c1"));

        ExceptionCase settled = cases.findByPaymentId(PAYMENT).orElseThrow();

        assertThat(settled.state()).isEqualTo(CaseState.SETTLED);
        JsonNode variableFilter = json(engine.calls(VARIABLE_SEARCH).getFirst().body()).get("filter");
        assertThat(variableFilter.get("processInstanceKey").toString()).contains(Long.toString(INSTANCE));
        assertThat(variableFilter.get("name").toString()).contains("exceptionOutcome");
    }

    @Test
    void anInstanceThatHoldsNoReadableCaseIsTheEnginesFault() {
        scriptInstance("COMPLETED", false, Map.of("paymentId", PAYMENT.value()));

        assertThatThrownBy(() -> cases.findByPaymentId(PAYMENT))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WORKFLOW_REJECTED));
    }

    @Test
    void listsTheOpenReviewTasksAsTheWorkQueueOnePageAtATime() {
        long second = INSTANCE + 1;
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(
            userTask(TASK, INSTANCE, "Task_OpsReview", "pay-tr-fast-ops", null),
            userTask(TASK + 1, second, "Task_OpsReview", "pay-tr-fast-ops", null)), "WzE3MzAwMDAwMDAwMDBd"));
        engine.answer(PROCESS_INSTANCE_SEARCH, 200, page(List.of(instance(INSTANCE, "ACTIVE", false)), "c1"));
        engine.answer(VARIABLE_SEARCH, 200, page(variables(INSTANCE, vars("schemeStatus", "PDNG")), "c1"));

        CasePage page = cases.findInReview(new CaseQuery(Optional.of(QueueName.of("pay-tr-fast-ops")), Optional.of(ReviewLevel.OPERATIONS), 2,
            Optional.empty()));

        // The second task's instance is not in the search yet: it is left out rather than shown half-read.
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(CaseState.IN_REVIEW);
            assertThat(item.reason()).contains(ExceptionReason.SCHEME_STATUS_UNCLEAR);
        });
        assertThat(page.nextPageToken()).contains(PageTokens.token("WzE3MzAwMDAwMDAwMDBd"));
        JsonNode taskSearch = json(engine.calls(USER_TASK_SEARCH).getFirst().body());
        assertThat(taskSearch.get("filter").get("candidateGroup").toString()).contains("pay-tr-fast-ops");
        assertThat(taskSearch.get("filter").get("elementId").asString()).isEqualTo("Task_OpsReview");
        assertThat(taskSearch.get("filter").get("state").toString()).contains("CREATED");
        assertThat(taskSearch.get("page").get("limit").asInt()).isEqualTo(2);

        cases.findInReview(new CaseQuery(Optional.empty(), Optional.of(ReviewLevel.SUPERVISOR), 2, page.nextPageToken()));
        JsonNode nextSearch = json(engine.calls(USER_TASK_SEARCH).get(1).body());
        assertThat(nextSearch.get("page").get("after").asString()).isEqualTo("WzE3MzAwMDAwMDAwMDBd");
        assertThat(nextSearch.get("filter").get("elementId").asString()).isEqualTo("Task_SupervisorReview");
    }

    @Test
    void anEmptyOrLastPageHasNoNextToken() {
        engine.answer(USER_TASK_SEARCH, 200, page(List.of(), null));
        assertThat(cases.findInReview(CaseQuery.firstPage(Optional.empty(), Optional.empty(), 5))).satisfies(page -> {
            assertThat(page.items()).isEmpty();
            assertThat(page.nextPageToken()).isEmpty();
        });
        assertThatThrownBy(() -> PageTokens.cursor("***"))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PAGE_REQUEST));
    }

    @Test
    void submitsTheDecisionByCompletingTheReviewTask() {
        engine.answer("POST /v2/user-tasks/" + TASK + "/completion", 204, null);

        cases.submit(decision());

        JsonNode completion = json(engine.calls("POST /v2/user-tasks/" + TASK + "/completion").getFirst().body());
        assertThat(completion.get("variables").get("exceptionDecision").asString()).isEqualTo("SETTLED");
    }

    @ParameterizedTest
    @CsvSource({"404,REVIEW_TASK_CHANGED", "409,REVIEW_TASK_CHANGED", "400,WORKFLOW_REJECTED", "403,WORKFLOW_REJECTED",
        "429,WORKFLOW_UNAVAILABLE", "500,WORKFLOW_UNAVAILABLE", "503,WORKFLOW_UNAVAILABLE"})
    void anEngineRefusalIsAnsweredInTheDomainsTerms(int status, ErrorCode expected) {
        engine.answer("POST /v2/user-tasks/" + TASK + "/completion", status, problem(status, "refused"));

        assertThatThrownBy(() -> cases.submit(decision()))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    void aSecondInstanceTheEngineRefusesIsAnExistingCase() {
        engine.answer(PROCESS_INSTANCES, 409, problem(409, "business id already in use"));
        assertThatThrownBy(() -> cases.open(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS));
    }

    @Test
    void aSlowOrAbsentEngineIsUnavailable() {
        engine.answer(PROCESS_INSTANCE_SEARCH, 200, page(List.of(), null), 3_000);
        assertThatThrownBy(() -> cases.findByPaymentId(PAYMENT))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WORKFLOW_UNAVAILABLE));

        engine.stop();
        assertThatThrownBy(() -> cases.findInReview(CaseQuery.firstPage(Optional.empty(), Optional.empty(), 5)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WORKFLOW_UNAVAILABLE));
    }

    @Test
    void aDomainAnswerFromInsideACallIsPassedOnAsItIs() {
        DomainException own = new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "own");
        assertThat(EngineCalls.translate("x", new java.util.concurrent.CompletionException(own), Map.of())).isSameAs(own);
    }

    @Test
    void aStringVariableIsMatchedByItsJsonForm() {
        assertThat(CamundaExceptionCases.jsonString("demo-tr-\"x\\")).isEqualTo("\"demo-tr-\\\"x\\\\\"");
    }

    @Test
    void theRecentWritesAreBounded() {
        RecentlyOpened recent = new RecentlyOpened(Duration.ofMinutes(2), 1, clock);
        recent.remember(new NewExceptionCase(PAYMENT, Jurisdiction.of("TR"), Rail.FAST), 1);
        recent.remember(new NewExceptionCase(PaymentId.of("demo-tr-unres-502"), Jurisdiction.of("TR"), Rail.FAST), 2);
        assertThat(recent.size()).isEqualTo(1);
        now.set(now.get().plus(Duration.ofMinutes(5)));
        recent.remember(new NewExceptionCase(PaymentId.of("demo-tr-unres-503"), Jurisdiction.of("TR"), Rail.FAST), 3);
        assertThat(recent.find(PaymentId.of("demo-tr-unres-503"))).isPresent();
        assertThat(recent.find(PAYMENT)).isEmpty();
    }

    static ExceptionDecisionSubmitted decision() {
        return new ExceptionDecisionSubmitted(UUID.randomUUID(), PAYMENT, INSTANCE, new ReviewTaskId(TASK), ReviewLevel.OPERATIONS,
            Optional.of(QueueName.of("pay-tr-fast-ops")), Decision.SETTLED, new Actor("ops.ayse"), InteractionId.random(),
            Instant.parse("2026-11-02T10:10:00Z"));
    }
}
