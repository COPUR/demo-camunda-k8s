package com.bank.bpo.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.bank.bpo.simulator.DomainSimulator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderSimulatorServiceTest {

    DomainSimulator simulator;
    HttpServer server;
    URI base;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        simulator = DomainSimulator.start().schemeStatuses("demo-tr-acsc-001", "ACSC");
        JwtVerifier verifier = new JwtVerifier(JwtVerifierTest.ISSUER, ProviderRole.PSG_TR.audience(), Set.of("svc-bpo-worker-payments"),
            kid -> JwtVerifierTest.KEYS.getPublic(), Clock.fixed(JwtVerifierTest.NOW, ZoneOffset.UTC));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new ProviderSimulatorService(ProviderRole.PSG_TR, verifier, simulator)::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        simulator.close();
    }

    @Test
    void aCallWithAValidTokenReachesTheSimulatedProvider() throws Exception {
        HttpResponse<String> response = get("api/v1/psg-tr/fast/instructions/demo-tr-acsc-001/status", token("svc-psg-tr"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ACSC");
    }

    @Test
    void aCallWithoutATokenOrForAnotherAudienceNeverReachesIt() throws Exception {
        assertThat(get("api/v1/psg-tr/fast/instructions/demo-tr-acsc-001/status", null).statusCode()).isEqualTo(401);
        assertThat(get("api/v1/psg-tr/fast/instructions/demo-tr-acsc-001/status", token("svc-acc-accounts-ledger")).statusCode()).isEqualTo(403);
        assertThat(simulator.calls()).isEmpty();
    }

    @Test
    void anotherProvidersOperationIsNotServed() throws Exception {
        assertThat(get("api/v1/holds/demo-tr-acsc-001/capture", token("svc-psg-tr")).statusCode()).isEqualTo(404);
        assertThat(get("api/v1/psg-tr/fast/instructions/..%2F..%2Fholds/status", token("svc-psg-tr")).statusCode()).isEqualTo(404);
        assertThat(simulator.calls()).isEmpty();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String audience) {
        return JwtVerifierTest.bearer(JwtVerifierTest.KEYS.getPrivate(), "RS256",
            JwtVerifierTest.claims(List.of(audience), "svc-bpo-worker-payments", 300));
    }
}
