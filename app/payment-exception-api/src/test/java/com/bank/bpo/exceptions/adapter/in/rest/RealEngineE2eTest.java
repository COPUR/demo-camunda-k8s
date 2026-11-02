package com.bank.bpo.exceptions.adapter.in.rest;

import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.key;
import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.problem;
import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.raiseJson;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.raiser;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.reader;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.bpo.exceptions.adapter.out.camunda.CamundaExceptionCases;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import com.bank.bpo.exceptions.domain.shared.Rail;
import io.camunda.client.CamundaClient;
import io.camunda.client.CredentialsProvider;
import io.camunda.client.api.response.ActivatedJob;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API against a real Camunda 8.10 orchestration cluster (e2e/run.sh starts one in Docker with authorizations on,
 * business id uniqueness on and the API's least-privilege grant). The engine adapter, its client and the engine are
 * real; the HTTP edge runs in MockMvc with test tokens, as in the other API tests. An admin client deploys the models
 * and stands in for the payments worker and the event bridge: it completes the jobs and publishes the status messages.
 *
 * <p>Not part of {@code test}: it runs only through {@code e2eTest}, which passes the engine's address and the paths
 * of the two password files (never the passwords) as system properties.
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "e2e.camunda.rest-address", matches = ".+")
@SpringBootTest(properties = {
    "management.server.port=",
    "bpo.exceptions.security.issuer=https://keycloak.test/auth/realms/camunda-platform",
    "bpo.exceptions.security.jwk-set-uri=https://keycloak.test/auth/realms/camunda-platform/protocol/openid-connect/certs",
    "bpo.exceptions.http.rate-limit.capacity=10000",
    "bpo.exceptions.http.rate-limit.refill-per-second=10000",
    "camunda.client.mode=self-managed",
    "camunda.client.prefer-rest-over-grpc=true",
    "camunda.client.auth.method=basic",
    "camunda.client.auth.username=svc-payment-exception-api"
})
@AutoConfigureMockMvc
class RealEngineE2eTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path MODELS = Path.of(System.getProperty("e2e.models", "../../models"));
    private static CamundaClient admin;

    @Autowired
    MockMvc mvc;

    @Autowired
    CamundaExceptionCases engine;

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("camunda.client.rest-address", () -> System.getProperty("e2e.camunda.rest-address"));
        registry.add("camunda.client.grpc-address", () -> System.getProperty("e2e.camunda.grpc-address", "http://127.0.0.1:26500"));
        registry.add("camunda.client.auth.password", () -> secret("e2e.camunda.api-password-file"));
    }

    @BeforeAll
    static void deployModels() throws IOException {
        URI address = URI.create(System.getProperty("e2e.camunda.rest-address"));
        CredentialsProvider credentials = CredentialsProvider.newBasicAuthCredentialsProviderBuilder()
            .username(System.getProperty("e2e.camunda.admin-user", "e2e-admin"))
            .password(secret("e2e.camunda.admin-password-file"))
            .build();
        admin = CamundaClient.newClientBuilder().restAddress(address).grpcAddress(address).preferRestOverGrpc(true)
            .credentialsProvider(credentials).defaultRequestTimeout(Duration.ofSeconds(20)).build();
        List<Path> models;
        try (var files = Files.list(MODELS)) {
            models = files.filter(Files::isRegularFile).sorted().toList();
        }
        var deploy = admin.newDeployResourceCommand().addResourceFile(models.getFirst().toString());
        for (Path model : models.subList(1, models.size())) {
            deploy = deploy.addResourceFile(model.toString());
        }
        deploy.send().join();
    }

    @AfterAll
    static void closeAdmin() {
        if (admin != null) {
            admin.close();
        }
    }

    /** EXC-001 end to end: raised through the API, four PDNG answers, ops review, a SETTLED decision, the hold captured. */
    @Test
    void aCaseRaisedThroughTheApiIsReviewedDecidedAndSettled() throws Exception {
        String paymentId = newPaymentId();
        String raiseKey = key();
        mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", raiseKey).content(raiseJson(paymentId)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/payment-exceptions/" + paymentId))
            .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", raiseKey).content(raiseJson(paymentId)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"));
        // A second raise under another key is refused: the engine search (or, until it catches up, this replica's
        // own record of what it opened) already knows the case.
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson(paymentId))).andReturn(), 409, "PEX-3002");

        for (int inquiry = 1; inquiry <= 4; inquiry++) {
            completeJob("psg.status-inquiry", job -> paymentId.equals(job.getVariable("sourceRef")), Map.of("schemeStatus", "PDNG"));
            if (inquiry < 4) {
                admin.newPublishMessageCommand().messageName("Msg_SchemeStatusReported").correlationKey(paymentId)
                    .timeToLive(Duration.ofSeconds(30)).send().join();
            }
        }

        JsonNode inReview = awaitCase(paymentId, c -> "CASE_STATE_IN_REVIEW".equals(c.path("state").asString()));
        assertThat(inReview.path("reason").asString()).isEqualTo("EXCEPTION_REASON_SCHEME_STATUS_TIMEOUT");
        assertThat(inReview.path("openReview").path("level").asString()).isEqualTo("REVIEW_LEVEL_OPERATIONS");
        assertThat(inReview.path("openReview").path("queue").asString()).isEqualTo("pay-tr-fast-ops");
        assertThat(listQueue("pay-tr-fast-ops", "REVIEW_LEVEL_OPERATIONS", 50)).contains(paymentId);

        String etag = inReview.path("etag").asString();
        String decisionKey = key();
        mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(opsReviewer()).contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag).header("Idempotency-Key", decisionKey).content("{\"decision\":\"DECISION_SETTLED\"}"))
            .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(opsReviewer()).contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag).header("Idempotency-Key", decisionKey).content("{\"decision\":\"DECISION_SETTLED\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotent-Replayed", "true"));
        // The task is gone, so the old tag no longer matches anything open.
        assertThat(mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(opsReviewer())
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", etag).header("Idempotency-Key", key())
                .content("{\"decision\":\"DECISION_REJECTED\"}")).andReturn().getResponse().getStatus()).isIn(409, 412);

        awaitCase(paymentId, c -> "CASE_STATE_RESOLVING".equals(c.path("state").asString()));
        long instance = inReview.path("workflowInstanceKey").asLong();
        completeJob("pay.record-exception-decision", job -> job.getProcessInstanceKey() == instance, Map.of("exceptionOutcome", "SETTLE"));
        completeJob("acc.capture-hold", job -> job.getProcessInstanceKey() == instance, Map.of());
        completeJob("pay.mark-settled", job -> job.getProcessInstanceKey() == instance, Map.of());
        completeJob("ntf.notify-payment", job -> job.getProcessInstanceKey() == instance, Map.of());

        JsonNode settled = awaitCase(paymentId, c -> "CASE_STATE_SETTLED".equals(c.path("state").asString()));
        assertThat(settled.path("decision").asString()).isEqualTo("DECISION_SETTLED");
        assertThat(settled.path("outcome").asString()).isEqualTo("OUTCOME_SETTLE");
    }

    /**
     * EXC-002 and EXC-003 on an instance the API did not start (as the sample-data loader starts them, without a
     * business id): the variable filter finds it, an unknown final status reads as unclear, ESCALATE moves it to the
     * supervisor queue, and the supervisor's REJECTED decision releases the hold.
     */
    @Test
    void anInstanceStartedElsewhereIsFoundEscalatedAndRejected() throws Exception {
        String paymentId = newPaymentId();
        admin.newCreateInstanceCommand().bpmnProcessId("pay-instant-exception").latestVersion()
            .variables(Map.of("paymentId", paymentId, "jurisdiction", "TR", "rail", "FAST")).send().join();
        completeJob("psg.status-inquiry", job -> paymentId.equals(job.getVariable("sourceRef")), Map.of("schemeStatus", "RCVD"));

        JsonNode opsReview = awaitCase(paymentId, c -> "CASE_STATE_IN_REVIEW".equals(c.path("state").asString()));
        assertThat(opsReview.path("reason").asString()).isEqualTo("EXCEPTION_REASON_SCHEME_STATUS_UNCLEAR");
        mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(opsReviewer()).contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", opsReview.path("etag").asString()).header("Idempotency-Key", key())
                .content("{\"decision\":\"DECISION_ESCALATE\"}"))
            .andExpect(status().isAccepted());

        // The reason is checked the moment the supervisor's task shows, not waited for: a reason that is wrong for a
        // moment (read skew between the task and the variable searches) must fail here.
        JsonNode supervisorReview = awaitCase(paymentId, c -> "REVIEW_LEVEL_SUPERVISOR".equals(c.path("openReview").path("level").asString()));
        assertThat(supervisorReview.path("reason").asString()).isEqualTo("EXCEPTION_REASON_OPERATIONS_ESCALATION");
        assertThat(supervisorReview.path("openReview").path("queue").asString()).isEqualTo("pay-tr-fast-supervisor");
        assertThat(queueEntries("pay-tr-fast-supervisor", "REVIEW_LEVEL_SUPERVISOR", 50))
            .filteredOn(c -> paymentId.equals(c.path("paymentId").asString()))
            .singleElement()
            .satisfies(c -> assertThat(c.path("reason").asString()).isEqualTo("EXCEPTION_REASON_OPERATIONS_ESCALATION"));
        // An operations reviewer is not a member of the supervisor queue.
        problem(mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(opsReviewer())
            .contentType(MediaType.APPLICATION_JSON).header("If-Match", supervisorReview.path("etag").asString())
            .header("Idempotency-Key", key()).content("{\"decision\":\"DECISION_REJECTED\"}")).andReturn(), 403, "PEX-2003");
        mvc.perform(post("/api/v1/payment-exceptions/" + paymentId + "/decisions").with(supervisor()).contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", supervisorReview.path("etag").asString()).header("Idempotency-Key", key())
                .content("{\"decision\":\"DECISION_REJECTED\"}"))
            .andExpect(status().isAccepted());

        long instance = supervisorReview.path("workflowInstanceKey").asLong();
        completeJob("pay.record-exception-decision", job -> job.getProcessInstanceKey() == instance, Map.of("exceptionOutcome", "RELEASE"));
        completeJob("acc.release-hold", job -> job.getProcessInstanceKey() == instance, Map.of());
        completeJob("pay.mark-rejected", job -> job.getProcessInstanceKey() == instance, Map.of());
        completeJob("ntf.notify-payment", job -> job.getProcessInstanceKey() == instance, Map.of());
        JsonNode rejected = awaitCase(paymentId, c -> "CASE_STATE_REJECTED".equals(c.path("state").asString()));
        assertThat(rejected.path("outcome").asString()).isEqualTo("OUTCOME_RELEASE");
    }

    /** With uniqueness on, the engine itself refuses a second instance for the same payment (the fence behind the lock). */
    @Test
    void theEngineRefusesASecondInstanceForTheSamePayment() {
        NewExceptionCase request = new NewExceptionCase(PaymentId.of(newPaymentId()), Jurisdiction.of("TR"), Rail.of("FAST"));
        assertThat(engine.open(request)).isPositive();
        assertThatThrownBy(() -> engine.open(request))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EXCEPTION_CASE_ALREADY_EXISTS));
    }

    /** The engine's own cursor survives the round trip through the opaque page token. */
    @Test
    void theReviewQueueIsReadAPageAtATime() throws Exception {
        Set<String> mine = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String paymentId = newPaymentId();
            admin.newCreateInstanceCommand().bpmnProcessId("pay-instant-exception").latestVersion()
                .variables(Map.of("paymentId", paymentId, "jurisdiction", "TR", "rail", "FAST")).send().join();
            completeJob("psg.status-inquiry", job -> paymentId.equals(job.getVariable("sourceRef")), Map.of("schemeStatus", "RCVD"));
            mine.add(paymentId);
        }
        for (String paymentId : mine) {
            awaitCase(paymentId, c -> "CASE_STATE_IN_REVIEW".equals(c.path("state").asString()));
        }
        List<String> seen = listQueue("pay-tr-fast-ops", "REVIEW_LEVEL_OPERATIONS", 1);
        assertThat(seen).containsAll(mine).doesNotHaveDuplicates();
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** A synthetic id no earlier run used: demo-tr-e2e- and twelve hex digits. */
    private static String newPaymentId() {
        byte[] random = new byte[6];
        ThreadLocalRandom.current().nextBytes(random);
        return "demo-tr-e2e-" + HexFormat.of().formatHex(random);
    }

    private static JwtRequestPostProcessor opsReviewer() {
        return token("ops.e2e", List.of("/pay-tr-fast-ops"), Tokens.READ, Tokens.DECIDE);
    }

    private static JwtRequestPostProcessor supervisor() {
        return token("supervisor.e2e", List.of("/pay-tr-fast-supervisor"), Tokens.READ, Tokens.DECIDE);
    }

    private static String secret(String property) {
        String file = System.getProperty(property);
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(property + " must name a file that holds the password");
        }
        try {
            return Files.readString(Path.of(file)).strip();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + property, e);
        }
    }

    /** Activates jobs of a type until the one the predicate picks shows up, completes it, and leaves the rest alone. */
    private static void completeJob(String type, Predicate<ActivatedJob> mine, Map<String, Object> variables) {
        ActivatedJob job = await("a " + type + " job", () -> admin.newActivateJobsCommand().jobType(type).maxJobsToActivate(32)
            .timeout(Duration.ofSeconds(10)).requestTimeout(Duration.ofSeconds(5)).send().join().getJobs().stream().filter(mine).findFirst()
            .orElse(null));
        admin.newCompleteCommand(job.getKey()).variables(variables).send().join();
    }

    private JsonNode awaitCase(String paymentId, Predicate<JsonNode> condition) {
        return await("case " + paymentId + " to match", () -> {
            try {
                MvcResult result = mvc.perform(get("/api/v1/payment-exceptions/" + paymentId).with(reader())).andReturn();
                if (result.getResponse().getStatus() != 200) {
                    return null;
                }
                JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
                return condition.test(body) ? body : null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Every payment id in a queue, read page by page with the API's own next-page token. */
    private List<String> listQueue(String queue, String level, int pageSize) throws Exception {
        return queueEntries(queue, level, pageSize).stream().map(c -> c.path("paymentId").asString()).toList();
    }

    /** Every case in a queue as the list shows it, read page by page with the API's own next-page token. */
    private List<JsonNode> queueEntries(String queue, String level, int pageSize) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        String token = "";
        for (int page = 0; page < 200; page++) {
            MvcResult result = mvc.perform(get("/api/v1/payment-exceptions").with(reader()).param("queue", queue).param("reviewLevel", level)
                .param("pageSize", String.valueOf(pageSize)).param("pageToken", token)).andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
            body.path("exceptionCases").forEach(entries::add);
            token = body.path("nextPageToken").asString();
            if (token.isEmpty()) {
                return entries;
            }
        }
        throw new AssertionError("more than 200 pages in " + queue);
    }

    private static <T> T await(String what, Supplier<T> probe) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("gave up after " + PATIENCE.toSeconds() + " s waiting for " + what);
    }
}
