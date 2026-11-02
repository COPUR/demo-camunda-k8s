package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.opsReviewer;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.raiser;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.reader;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.bpo.exceptions.application.service.FakeCases;
import com.bank.bpo.exceptions.contract.v1.CaseState;
import com.bank.bpo.exceptions.contract.v1.Decision;
import com.bank.bpo.exceptions.contract.v1.DecisionReceipt;
import com.bank.bpo.exceptions.contract.v1.ExceptionCase;
import com.bank.bpo.exceptions.contract.v1.Rail;
import com.bank.bpo.exceptions.contract.v1.RaiseExceptionCaseRequest;
import com.bank.bpo.exceptions.contract.v1.SubmitDecisionRequest;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCaseRaised;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionReason;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTask;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewTaskId;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ApiTest
class PaymentExceptionApiTest {

    static final MediaType PROTOBUF = MediaType.parseMediaType(ProtobufMedia.PROTOBUF_VALUE);
    static final PaymentId IN_REVIEW = PaymentId.of("demo-tr-unres-201");
    static final long TASK = 2251799813685400L;

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeCases fake;

    @BeforeEach
    void reset() {
        fake.cases.clear();
        fake.opened.clear();
        fake.submitted.clear();
        fake.published.clear();
        fake.failOpen = null;
        fake.failSubmit = null;
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String raiseJson(String paymentId) {
        return "{\"paymentId\":\"" + paymentId + "\",\"jurisdiction\":\"TR\",\"rail\":\"RAIL_FAST\"}";
    }

    void seedInReview() {
        fake.cases.put(IN_REVIEW, new com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase(IN_REVIEW, Jurisdiction.of("TR"),
            com.bank.bpo.exceptions.domain.shared.Rail.FAST, com.bank.bpo.exceptions.domain.casemanagement.CaseState.IN_REVIEW,
            Optional.empty(), Optional.of(ExceptionReason.SCHEME_STATUS_TIMEOUT),
            Optional.of(new ReviewTask(new ReviewTaskId(TASK), ReviewLevel.OPERATIONS, Optional.of(QueueName.of("pay-tr-fast-ops")),
                Optional.of(Instant.parse("2026-11-02T10:35:00Z")), Optional.empty())),
            Optional.empty(), Optional.empty(), false, Instant.parse("2026-11-02T10:00:00Z"), 2251799813685300L));
    }

    @Test
    void raisesACaseAndAnswersWithTheResourceItsTagAndTheReplayFlag() throws Exception {
        mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-101")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/payment-exceptions/demo-tr-unres-101"))
            .andExpect(header().string("ETag", "\"awaiting_scheme_status\""))
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.paymentId").value("demo-tr-unres-101"))
            .andExpect(jsonPath("$.state").value("CASE_STATE_AWAITING_SCHEME_STATUS"))
            .andExpect(jsonPath("$.rail").value("RAIL_FAST"))
            .andExpect(jsonPath("$.incident").value(false))
            .andExpect(jsonPath("$.decision").value("DECISION_UNSPECIFIED"))
            .andExpect(jsonPath("$.etag").value("\"awaiting_scheme_status\""));

        assertThat(fake.published).singleElement().isInstanceOfSatisfying(ExceptionCaseRaised.class,
            event -> assertThat(event.actor().subject()).isEqualTo("svc-bpo-worker-payments"));
    }

    @Test
    void aRetryWithTheSameKeyGetsTheFirstAnswerAndOpensNothing() throws Exception {
        String key = key();
        for (String replayed : List.of("false", "true")) {
            mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key).content(raiseJson("demo-tr-unres-102")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", replayed));
        }
        assertThat(fake.opened).hasSize(1);
    }

    @Test
    void theSameKeyForAnotherRequestIsRefused() throws Exception {
        String key = key();
        mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(raiseJson("demo-tr-unres-103")))
            .andExpect(status().isCreated());
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content(raiseJson("demo-tr-unres-104"))).andReturn(), 422, "PEX-3006");
    }

    @Test
    void speaksBinaryProtobufBothWays() throws Exception {
        byte[] body = RaiseExceptionCaseRequest.newBuilder().setPaymentId("demo-tr-unres-105").setJurisdiction("TR").setRail(Rail.RAIL_FAST)
            .build().toByteArray();
        MvcResult result = mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(PROTOBUF).accept(PROTOBUF)
                .header("Idempotency-Key", key()).content(body))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(PROTOBUF))
            .andReturn();

        ExceptionCase created = ExceptionCase.parseFrom(result.getResponse().getContentAsByteArray());
        assertThat(created.getPaymentId()).isEqualTo("demo-tr-unres-105");
        assertThat(created.getState()).isEqualTo(CaseState.CASE_STATE_AWAITING_SCHEME_STATUS);
        assertThat(created.getWorkflowInstanceKey()).isPositive();
    }

    @Test
    void refusesFieldsTheContractDoesNotDefineInJsonAndInBinary() throws Exception {
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key())
            .content("{\"paymentId\":\"demo-tr-unres-106\",\"jurisdiction\":\"TR\",\"rail\":\"RAIL_FAST\",\"amount\":\"1000\"}")).andReturn(),
            400, "PEX-1008");

        byte[] known = RaiseExceptionCaseRequest.newBuilder().setPaymentId("demo-tr-unres-106").setJurisdiction("TR").setRail(Rail.RAIL_FAST)
            .build().toByteArray();
        byte[] withUnknownField = new byte[known.length + 3];
        System.arraycopy(known, 0, withUnknownField, 0, known.length);
        withUnknownField[known.length] = (byte) 0x98; // field 99, varint
        withUnknownField[known.length + 1] = 0x06;
        withUnknownField[known.length + 2] = 0x01;
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(PROTOBUF).header("Idempotency-Key", key())
            .content(withUnknownField)).andReturn(), 400, "PEX-1008");
        assertThat(fake.opened).isEmpty();
    }

    @Test
    void validatesTheRequestIntoValueObjects() throws Exception {
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .content(raiseJson("demo-tr-unres-107"))).andReturn(), 400, "PEX-1006");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "short").content(raiseJson("demo-tr-unres-107"))).andReturn(), 400, "PEX-1005");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson("TR000000000000000000000000"))).andReturn(), 400, "PEX-1001");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content("{\"paymentId\":\"demo-tr-unres-107\",\"jurisdiction\":\"TR\"}")).andReturn(),
            400, "PEX-1003");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content("{\"paymentId\":\"demo-tr-unres-107\",\"jurisdiction\":\"DE\",\"rail\":\"RAIL_FAST\"}"))
            .andReturn(), 422, "PEX-4002");
        assertThat(fake.opened).isEmpty();
    }

    @Test
    void aSecondCaseForTheSamePaymentIsAConflict() throws Exception {
        mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-108")))
            .andExpect(status().isCreated());
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-108"))).andReturn(), 409, "PEX-3002");
    }

    @Test
    void eachOperationNeedsItsScopeAndEveryCallAToken() throws Exception {
        MvcResult forbidden = mvc.perform(post("/api/v1/payment-exceptions").with(reader()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-109"))).andReturn();
        problem(forbidden, 403, "PEX-2002");
        assertThat(forbidden.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer").contains("insufficient_scope");
        MvcResult anonymous = mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-109")).andReturn();
        problem(anonymous, 401, "PEX-2001");
        assertThat(anonymous.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        problem(mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-109").with(token("nobody", List.of()))).andReturn(), 403, "PEX-2002");
        assertThat(fake.opened).isEmpty();
    }

    @Test
    void readsACaseWithItsTagAndAnswersAConditionalReadWithNotModified() throws Exception {
        seedInReview();
        mvc.perform(get("/api/v1/payment-exceptions/" + IN_REVIEW).with(reader()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"rt-" + TASK + "\""))
            .andExpect(jsonPath("$.state").value("CASE_STATE_IN_REVIEW"))
            .andExpect(jsonPath("$.reason").value("EXCEPTION_REASON_SCHEME_STATUS_TIMEOUT"))
            .andExpect(jsonPath("$.openReview.taskId").value(String.valueOf(TASK)))
            .andExpect(jsonPath("$.openReview.level").value("REVIEW_LEVEL_OPERATIONS"))
            .andExpect(jsonPath("$.openReview.queue").value("pay-tr-fast-ops"))
            .andExpect(jsonPath("$.openReview.allowedDecisions", hasSize(3)));

        mvc.perform(get("/api/v1/payment-exceptions/" + IN_REVIEW).with(reader()).header("If-None-Match", "\"rt-" + TASK + "\""))
            .andExpect(status().isNotModified());
    }

    @Test
    void aPaymentWithoutACaseIsNotFound() throws Exception {
        problem(mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-404").with(reader())).andReturn(), 404, "PEX-3001");
        problem(mvc.perform(get("/api/v1/payment-exceptions/not-a-demo-id").with(reader())).andReturn(), 400, "PEX-1001");
    }

    @Test
    void listsTheWorkQueueAPageAtATime() throws Exception {
        seedInReview();
        mvc.perform(get("/api/v1/payment-exceptions").with(reader())
                .param("queue", "pay-tr-fast-ops").param("reviewLevel", "REVIEW_LEVEL_OPERATIONS").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exceptionCases", hasSize(1)))
            .andExpect(jsonPath("$.exceptionCases[0].paymentId").value(IN_REVIEW.value()))
            .andExpect(jsonPath("$.nextPageToken").value(""));

        assertThat(fake.queries).last().satisfies(query -> {
            assertThat(query.queue()).contains(QueueName.of("pay-tr-fast-ops"));
            assertThat(query.level()).contains(ReviewLevel.OPERATIONS);
            assertThat(query.pageSize()).isEqualTo(10);
        });

        mvc.perform(get("/api/v1/payment-exceptions").with(reader())).andExpect(status().isOk());
        assertThat(fake.queries).last().satisfies(query -> assertThat(query.pageSize()).isEqualTo(20));
    }

    @Test
    void refusesPagesItCannotServe() throws Exception {
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("pageSize", "many")).andReturn(), 400, "PEX-1007");
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("pageSize", "1000")).andReturn(), 400, "PEX-1007");
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("reviewLevel", "BOSS")).andReturn(), 400, "PEX-1007");
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("reviewLevel", "REVIEW_LEVEL_UNSPECIFIED")).andReturn(),
            400, "PEX-1007");
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("pageToken", "not a token!")).andReturn(), 400, "PEX-1007");
        problem(mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("queue", "Not A Queue")).andReturn(), 400, "PEX-1009");
    }

    @Test
    void aQueueMemberDecidesOnTheViewTheyRead() throws Exception {
        seedInReview();
        mvc.perform(post("/api/v1/payment-exceptions/" + IN_REVIEW + "/decisions").with(opsReviewer())
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"rt-" + TASK + "\"").header("Idempotency-Key", key())
                .content("{\"decision\":\"DECISION_SETTLED\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/v1/payment-exceptions/" + IN_REVIEW))
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.reviewTaskId").value(String.valueOf(TASK)))
            .andExpect(jsonPath("$.decision").value("DECISION_SETTLED"))
            .andExpect(jsonPath("$.level").value("REVIEW_LEVEL_OPERATIONS"))
            .andExpect(jsonPath("$.submittedAt", startsWith("20")));

        assertThat(fake.submitted).singleElement().satisfies(submitted -> {
            assertThat(submitted.reviewTaskId().value()).isEqualTo(TASK);
            assertThat(submitted.actor().subject()).isEqualTo("ops.ayse");
        });
        assertThat(fake.published).singleElement().isInstanceOf(ExceptionDecisionSubmitted.class);
    }

    @Test
    void decidesInBinaryProtobufToo() throws Exception {
        seedInReview();
        MvcResult result = mvc.perform(post("/api/v1/payment-exceptions/" + IN_REVIEW + "/decisions").with(opsReviewer())
                .contentType(PROTOBUF).accept(PROTOBUF).header("If-Match", "\"rt-" + TASK + "\"").header("Idempotency-Key", key())
                .content(SubmitDecisionRequest.newBuilder().setDecision(Decision.DECISION_ESCALATE).build().toByteArray()))
            .andExpect(status().isAccepted())
            .andReturn();
        assertThat(DecisionReceipt.parseFrom(result.getResponse().getContentAsByteArray()).getDecision()).isEqualTo(Decision.DECISION_ESCALATE);
    }

    @Test
    void aDecisionNamesTheViewItWasTakenOn() throws Exception {
        seedInReview();
        String path = "/api/v1/payment-exceptions/" + IN_REVIEW + "/decisions";
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 428, "PEX-3005");
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "*").content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 428, "PEX-3005");
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "\"rt-1\"").content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 412, "PEX-3004");
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "W/\"rt-" + TASK + "\"").content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 412, "PEX-3004");
        assertThat(fake.submitted).isEmpty();
    }

    @Test
    void onlyAMemberOfTheRoutedQueueDecidesAndOnlyWhatTheLevelAllows() throws Exception {
        seedInReview();
        String path = "/api/v1/payment-exceptions/" + IN_REVIEW + "/decisions";
        problem(mvc.perform(post(path).with(token("sup.mehmet", List.of("/pay-tr-fast-supervisor"), Tokens.DECIDE))
            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key()).header("If-Match", "\"rt-" + TASK + "\"")
            .content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 403, "PEX-2003");
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "\"rt-" + TASK + "\"").content("{\"decision\":\"DECISION_EXPIRED\"}")).andReturn(), 422, "PEX-4001");
        problem(mvc.perform(post(path).with(opsReviewer()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "\"rt-" + TASK + "\"").content("{}")).andReturn(), 400, "PEX-1004");
        problem(mvc.perform(post(path).with(reader()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
            .header("If-Match", "\"rt-" + TASK + "\"").content("{\"decision\":\"DECISION_SETTLED\"}")).andReturn(), 403, "PEX-2002");
        assertThat(fake.submitted).isEmpty();
    }

    /** Asserts an RFC 9457 problem carrying the catalogued code, its type link and the interaction id. */
    static void problem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).isEqualTo(ProblemWriter.PROBLEM_JSON);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"" + code + "\"")
            .contains("\"type\":\"/api/v1/exception-catalog/errors/" + code + "\"")
            .contains("\"status\":" + status)
            .contains("\"interactionId\":\"" + result.getResponse().getHeader("x-fapi-interaction-id") + "\"");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
