package com.bank.bpo.exceptions.adapter.in.rest;

import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.key;
import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.problem;
import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.raiseJson;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.raiser;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.bpo.exceptions.application.service.FakeCases;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** What every response shares: problem format, media types, size limit, correlation, headers, probes. */
@ApiTest
class HttpEdgeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeCases fake;

    @AfterEach
    void reset() {
        fake.failOpen = null;
    }

    @Test
    void echoesTheCallersInteractionIdAndPutsItInTheProblem() throws Exception {
        String id = "7f9c1d6e-2f0a-4c55-9a3b-1e2d3c4b5a69";
        MvcResult result = mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-404").with(reader()).header("x-fapi-interaction-id", id))
            .andExpect(header().string("x-fapi-interaction-id", id)).andReturn();
        problem(result, 404, "PEX-3001");

        String generated = mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-404").with(reader())
                .header("x-fapi-interaction-id", "not-a-uuid"))
            .andReturn().getResponse().getHeader("x-fapi-interaction-id");
        assertThat(generated).isNotEqualTo("not-a-uuid").matches("[0-9a-f-]{36}");
    }

    @Test
    void refusesBodiesLargerThanTheLimitBeforeParsingThem() throws Exception {
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content("{\"paymentId\":\"" + "x".repeat(4096) + "\"}")).andReturn(), 413, "PEX-5001");
    }

    @Test
    void speaksOnlyJsonAndProtobuf() throws Exception {
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.TEXT_PLAIN)
            .header("Idempotency-Key", key()).content("payment_id: \"demo-tr-unres-301\"")).andReturn(), 415, "PEX-5002");
        problem(mvc.perform(get("/api/v1/exception-catalog").with(reader()).accept(MediaType.APPLICATION_XML)).andReturn(), 406, "PEX-5003");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content("{not json")).andReturn(), 400, "PEX-1008");
        problem(mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key())).andReturn(), 400, "PEX-1008");
    }

    @Test
    void namesTheMethodsAResourceSupports() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-301/decisions").with(reader())).andReturn();
        problem(result, 405, "PEX-5005");
        assertThat(result.getResponse().getHeader("Allow")).isEqualTo("POST");
    }

    @Test
    void anUnknownPathIsNotFoundAndAnUnlistedOperationIsRefused() throws Exception {
        problem(mvc.perform(get("/api/v1/payment-exceptions/demo-tr-unres-301/history").with(reader())).andReturn(), 404, "PEX-5006");
        problem(mvc.perform(delete("/api/v1/payment-exceptions/demo-tr-unres-301").with(reader())).andReturn(), 403, "PEX-2002");
        problem(mvc.perform(get("/actuator/env").with(reader())).andReturn(), 403, "PEX-2002");
    }

    @Test
    void aFailingEngineIsA503WithRetryAfterAndAnUnexpectedFailureLeaksNothing() throws Exception {
        fake.failOpen = new DomainException(ErrorCode.WORKFLOW_UNAVAILABLE, "the engine did not answer in time");
        MvcResult unavailable = mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-302"))).andReturn();
        problem(unavailable, 503, "PEX-9001");
        assertThat(unavailable.getResponse().getHeader("Retry-After")).isEqualTo("5");

        fake.failOpen = new IllegalStateException("connection to camunda-zeebe-gateway:8080 reset");
        MvcResult internal = mvc.perform(post("/api/v1/payment-exceptions").with(raiser()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key()).content(raiseJson("demo-tr-unres-303"))).andReturn();
        problem(internal, 500, "PEX-9999");
        assertThat(internal.getResponse().getContentAsString()).doesNotContain("camunda-zeebe-gateway").doesNotContain("Exception");
    }

    @Test
    void securityHeadersAreOnEveryResponse() throws Exception {
        mvc.perform(get("/api/v1/exception-catalog").with(reader()))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void theProbesNeedNoToken() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
