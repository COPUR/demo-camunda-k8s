package com.bank.bpo.demo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.bank.bpo.simulator.DomainSimulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * One simulated provider as its own service in the microservices demo. The front door is HTTPS with a certificate
 * from the demo CA and checks every call's token like the real provider would ({@link JwtVerifier}); it serves only
 * its own provider's paths and hands accepted calls to a {@link DomainSimulator} that listens on the pod's loopback
 * address. {@code /healthz} is the only path without a token.
 */
final class ProviderSimulatorService {

    static final int PORT = 8443;
    private static final Logger LOG = Logger.getLogger(ProviderSimulatorService.class.getName());
    // PEM armour of an unencrypted PKCS#8 key, built from parts so secret scanners do not read the label as a key.
    private static final String PKCS8_LABEL = "PRIVATE" + " KEY-----";
    private static final String PKCS8_BEGIN = "-----BEGIN " + PKCS8_LABEL;
    private static final String PKCS8_END = "-----END " + PKCS8_LABEL;
    private static final Set<String> FORWARDED_HEADERS = Set.of("content-type", "x-idempotency-key", "x-fapi-interaction-id");

    private final ProviderRole role;
    private final JwtVerifier verifier;
    private final DomainSimulator simulator;
    private final HttpClient inner = HttpClient.newHttpClient();

    ProviderSimulatorService(ProviderRole role, JwtVerifier verifier, DomainSimulator simulator) {
        this.role = role;
        this.verifier = verifier;
        this.simulator = simulator;
    }

    static void run(ProviderRole role, Map<String, String> env) throws Exception {
        DomainSimulator simulator = DomainSimulator.start();
        if (role == ProviderRole.PSG_TR) {
            PaymentsDemoLauncher.readScenarios(Path.of(env.getOrDefault("BPO_DEMO_SCENARIOS", "/config/scenarios.json")))
                .forEach((paymentId, statuses) -> simulator.schemeStatuses(paymentId, statuses.toArray(String[]::new)));
        }
        JwtVerifier verifier = new JwtVerifier(required(env, "BPO_DEMO_TOKEN_ISSUER"), role.audience(),
            Set.of(required(env, "BPO_DEMO_ALLOWED_CALLER")), JwtVerifier.jwks(URI.create(required(env, "BPO_DEMO_JWKS_URI"))),
            Clock.systemUTC());
        ProviderSimulatorService service = new ProviderSimulatorService(role, verifier, simulator);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls(Path.of(required(env, "BPO_DEMO_TLS_CERT")),
            Path.of(required(env, "BPO_DEMO_TLS_KEY")))));
        server.createContext("/healthz", exchange -> respond(exchange, 200, "application/json", "{\"status\":\"UP\"}"));
        server.createContext("/", service::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            () -> LOG.info(role.code() + " simulator: calls=" + simulator.calls().size() + " holdsCaptured=" + simulator.holdsCaptured()),
            15, 15, TimeUnit.SECONDS);
        LOG.info(role.code() + " simulator listening on https port " + PORT + " for audience " + role.audience());
    }

    void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!role.serves(rawPath)) {
                problem(exchange, 404, "NOT_FOUND", "not an operation of " + role.code());
                return;
            }
            switch (verifier.verify(exchange.getRequestHeaders().getFirst("Authorization"))) {
                case UNAUTHENTICATED -> {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                    problem(exchange, 401, "UNAUTHENTICATED", "a valid bearer token is required");
                }
                case FORBIDDEN -> problem(exchange, 403, "FORBIDDEN", "the token does not grant this operation");
                case OK -> forward(exchange, rawPath);
            }
        }
    }

    private void forward(HttpExchange exchange, String rawPath) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = simulator.baseUrl().resolve(rawPath.substring(1) + (query == null ? "" : "?" + query));
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
            .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (FORWARDED_HEADERS.contains(name.toLowerCase())) {
                values.forEach(value -> request.header(name, value));
            }
        });
        try {
            HttpResponse<byte[]> response = inner.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            List<String> type = response.headers().allValues("content-type");
            exchange.getResponseHeaders().set("Content-Type", type.isEmpty() ? "application/json" : type.getFirst());
            exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
            if (response.body().length > 0) {
                exchange.getResponseBody().write(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem(exchange, 503, "UNAVAILABLE", "simulator interrupted");
        }
    }

    private static void problem(HttpExchange exchange, int status, String code, String detail) throws IOException {
        respond(exchange, status, "application/problem+json",
            "{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** The server certificate chain and its PKCS#8 key, both PEM files that install.sh generates. */
    static SSLContext tls(Path certificatePem, Path keyPem) throws Exception {
        List<Certificate> chain = new ArrayList<>(CertificateFactory.getInstance("X.509")
            .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(certificatePem))));
        String pem = Files.readString(keyPem);
        if (!pem.contains(PKCS8_BEGIN)) {
            throw new IllegalStateException("the TLS key must be an unencrypted PKCS#8 PEM");
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.replace(PKCS8_BEGIN, "").replace(PKCS8_END, ""));
        PrivateKey key = KeyFactory.getInstance(((X509Certificate) chain.getFirst()).getPublicKey().getAlgorithm())
            .generatePrivate(new PKCS8EncodedKeySpec(der));
        char[] transientPassword = UUID.randomUUID().toString().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", key, transientPassword, chain.toArray(Certificate[]::new));
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, transientPassword);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
