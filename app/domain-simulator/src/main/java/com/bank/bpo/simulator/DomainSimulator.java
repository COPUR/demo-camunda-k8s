package com.bank.bpo.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for cus, cmp, acc, psg-tr, pay and ntf while the operations the onboarding and payments workers
 * call are requested but not yet in the catalog (contracts/expectations/*.yaml lists what the workers need). It keeps the behaviour that
 * matters to the process: a replayed idempotency key returns the first answer, a key reused with another
 * body is refused, a response can be lost after the effect happened, and a request without the fields its
 * expectation names is refused with 400, so a worker that drifts from the contract fails here too.
 */
public final class DomainSimulator implements AutoCloseable {

    public record Call(String method, String path, String idempotencyKey, String interactionId, Map<String, Object> body) { }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Pattern KYC = Pattern.compile("/api/v1/applications/([^/]+)/kyc");
    private static final Pattern OPEN = Pattern.compile("/api/v1/account-applications/([^/]+)/open");
    private static final Pattern REJECT = Pattern.compile("/api/v1/account-applications/([^/]+)/reject");
    private static final Pattern DECISION = Pattern.compile("/api/v1/compliance/review-cases/([^/]+)/decision");
    private static final Pattern SCHEME_STATUS = Pattern.compile("/api/v1/psg-tr/fast/instructions/([^/]+)/status");
    private static final Pattern CAPTURE = Pattern.compile("/api/v1/holds/([^/]+)/capture");
    private static final Pattern RELEASE = Pattern.compile("/api/v1/holds/([^/]+)/release");
    private static final Pattern EXCEPTION_DECISION = Pattern.compile("/api/v1/payments/([^/]+)/exception/decision");
    private static final Pattern MARK_SETTLED = Pattern.compile("/api/v1/payments/([^/]+)/exception/settle");
    private static final Pattern MARK_REJECTED = Pattern.compile("/api/v1/payments/([^/]+)/exception/reject");

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, String> kycByApplication = new HashMap<>();
    private final Map<String, String> screeningByApplication = new HashMap<>();
    private record Failure(int status, String code, int times) { }
    private record Raw(int status, String body, int times) { }

    private final Map<String, Raw> rawAnswers = new HashMap<>();

    private final Map<String, Failure> failuresBefore = new HashMap<>();
    private int tokenExpiresIn = 300;
    private int tokensIssued;
    private final Map<String, Integer> lostResponses = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Map<String, Object>> openedByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> openBodyByKey = new HashMap<>();
    private final Map<String, String> applicationByCase = new HashMap<>();
    private final Map<String, String> decisionByCase = new HashMap<>();
    private final Map<String, List<String>> schemeStatusesByPayment = new HashMap<>();
    private final Map<String, Map<String, Object>> capturedByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> captureBodyByKey = new HashMap<>();
    private final Map<String, Integer> releasesByPayment = new HashMap<>();
    private final Map<String, String> exceptionDecisionByPayment = new HashMap<>();
    private int sequence;

    private DomainSimulator(HttpServer server) {
        this.server = server;
    }

    public static DomainSimulator start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            DomainSimulator simulator = new DomainSimulator(server);
            server.createContext("/", simulator::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return simulator;
        } catch (IOException e) {
            throw new IllegalStateException("simulator did not start", e);
        }
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- scenario -----------------------------------------------------------------------------------

    public synchronized DomainSimulator kyc(String applicationId, String status) {
        kycByApplication.put(applicationId, status);
        return this;
    }

    /** PASS, REVIEW or FAIL: the decision values of the catalog's ComplianceScreeningResponse. */
    public synchronized DomainSimulator screening(String applicationId, String decision) {
        screeningByApplication.put(applicationId, decision);
        return this;
    }

    /**
     * The scheme statuses psg-tr answers for the payment, one per inquiry in order; the last one is repeated.
     * A payment without a scenario is ACSC.
     */
    public synchronized DomainSimulator schemeStatuses(String paymentId, String... statuses) {
        schemeStatusesByPayment.put(paymentId, new ArrayList<>(List.of(statuses)));
        return this;
    }

    /** The next {@code times} calls to the operation answer {@code status} before they have any effect. */
    public synchronized DomainSimulator failBeforeEffect(String operation, int times) {
        return failWith(operation, 503, "UNAVAILABLE", times);
    }

    /** The next {@code times} calls to the operation answer the given status and code before they have any effect. */
    public synchronized DomainSimulator failWith(String operation, int status, String code, int times) {
        failuresBefore.put(operation, new Failure(status, code, times));
        return this;
    }

    /** The next {@code times} calls to the operation answer {@code status} with {@code body} verbatim, before any effect. */
    public synchronized DomainSimulator answerRaw(String operation, int status, String body, int times) {
        rawAnswers.put(operation, new Raw(status, body, times));
        return this;
    }

    /** Seconds the issued tokens say they live (the token endpoint is POST /token). */
    public synchronized DomainSimulator tokenLifetimeSeconds(int seconds) {
        tokenExpiresIn = seconds;
        return this;
    }

    public synchronized int tokensIssued() {
        return tokensIssued;
    }

    /** The next {@code times} calls to the operation take effect and then answer 503 (the response is lost). */
    public synchronized DomainSimulator loseResponse(String operation, int times) {
        lostResponses.put(operation, times);
        return this;
    }

    public synchronized void reset() {
        kycByApplication.clear();
        screeningByApplication.clear();
        failuresBefore.clear();
        rawAnswers.clear();
        lostResponses.clear();
        tokensIssued = 0;
        tokenExpiresIn = 300;
        calls.clear();
        openedByKey.clear();
        openBodyByKey.clear();
        applicationByCase.clear();
        decisionByCase.clear();
        schemeStatusesByPayment.clear();
        capturedByKey.clear();
        captureBodyByKey.clear();
        releasesByPayment.clear();
        exceptionDecisionByPayment.clear();
    }

    // ---- observations -------------------------------------------------------------------------------

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Call> callsTo(String pathPrefix) {
        return calls.stream().filter((c) -> c.path().startsWith(pathPrefix)).toList();
    }

    /** Accounts created by open-account, one per distinct idempotency key. */
    public synchronized int accountsOpened() {
        return openedByKey.size();
    }

    /** Holds captured by capture-hold, one per distinct idempotency key. */
    public synchronized int holdsCaptured() {
        return capturedByKey.size();
    }

    public synchronized int holdsReleased(String paymentId) {
        return releasesByPayment.getOrDefault(paymentId, 0);
    }

    public synchronized String exceptionDecisionRecordedFor(String paymentId) {
        return exceptionDecisionByPayment.get(paymentId);
    }

    public synchronized String decisionRecordedFor(String applicationId) {
        return decisionByCase.entrySet().stream()
            .filter((e) -> applicationId.equals(applicationByCase.get(e.getKey())))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // ---- server -------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String key = exchange.getRequestHeaders().getFirst("x-idempotency-key");
        String interaction = exchange.getRequestHeaders().getFirst("x-fapi-interaction-id");
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> body = raw.isBlank() ? Map.of() : raw.startsWith("{") ? json.readValue(raw, MAP) : form(raw);
        Reply reply;
        synchronized (this) {
            calls.add(new Call(method, path, key, interaction, body));
            reply = route(method, path, key, body);
        }
        byte[] bytes = (reply.raw() != null ? reply.raw() : json.writeValueAsString(reply.body())).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** An application/x-www-form-urlencoded body, as the token endpoint receives it. */
    private static Map<String, Object> form(String raw) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }

    private record Reply(int status, Map<String, Object> body, String raw) {
        Reply(int status, Map<String, Object> body) {
            this(status, body, null);
        }
    }

    private Reply error(int status, String code) {
        return new Reply(status, Map.of("code", code, "message", code));
    }

    private Reply route(String method, String path, String key, Map<String, Object> body) {
        String operation = operationOf(method, path);
        if (operation == null) {
            return error(404, "NOT_FOUND");
        }
        Failure failure = failuresBefore.get(operation);
        if (failure != null && failure.times() > 0) {
            failuresBefore.put(operation, new Failure(failure.status(), failure.code(), failure.times() - 1));
            return error(failure.status(), failure.code());
        }
        Raw raw = rawAnswers.get(operation);
        if (raw != null && raw.times() > 0) {
            rawAnswers.put(operation, new Raw(raw.status(), raw.body(), raw.times() - 1));
            return new Reply(raw.status(), Map.of(), raw.body());
        }
        String missing = missingField(operation, body);
        if (missing != null) {
            return error(400, "INVALID_" + missing.toUpperCase());
        }
        Reply reply = apply(operation, path, key, body);
        if (reply.status() < 300 && consume(lostResponses, operation)) {
            return error(503, "UNAVAILABLE");
        }
        return reply;
    }

    private static String operationOf(String method, String path) {
        if (method.equals("GET") && KYC.matcher(path).matches()) return "kyc-status";
        if (method.equals("POST") && path.equals("/api/v1/compliance/onboarding-screenings")) return "screen";
        if (method.equals("POST") && path.equals("/api/v1/compliance/review-cases")) return "open-review-case";
        if (method.equals("POST") && DECISION.matcher(path).matches()) return "record-review-decision";
        if (method.equals("POST") && OPEN.matcher(path).matches()) return "open-account";
        if (method.equals("POST") && REJECT.matcher(path).matches()) return "reject-application";
        if (method.equals("POST") && path.equals("/api/v1/psg-tr/alias-offers")) return "alias-offer";
        if (method.equals("POST") && path.equals("/api/v1/notifications")) return "notify";
        if (method.equals("POST") && path.equals("/token")) return "token";
        if (method.equals("GET") && SCHEME_STATUS.matcher(path).matches()) return "scheme-status";
        if (method.equals("POST") && CAPTURE.matcher(path).matches()) return "capture-hold";
        if (method.equals("POST") && RELEASE.matcher(path).matches()) return "release-hold";
        if (method.equals("POST") && EXCEPTION_DECISION.matcher(path).matches()) return "record-exception-decision";
        if (method.equals("POST") && MARK_SETTLED.matcher(path).matches()) return "mark-settled";
        if (method.equals("POST") && MARK_REJECTED.matcher(path).matches()) return "mark-rejected";
        return null;
    }

    private static final List<String> JURISDICTIONS = List.of("AE", "TR");
    private static final List<String> REVIEW_DECISIONS = List.of("CLEAR", "REJECT", "EXPIRED");
    private static final List<String> REJECTION_REASONS = List.of("KYC_REJECTED", "KYC_TIMEOUT", "SCREENING_REJECTED");
    private static final List<String> PURPOSES = List.of("ACCOUNT_OPENED", "ACCOUNT_APPLICATION_REJECTED", "PAYMENT_SETTLED", "PAYMENT_REJECTED");
    private static final List<String> EXCEPTION_DECISIONS = List.of("SETTLED", "REJECTED", "EXPIRED");

    /** The request fields contracts/expectations names for each operation; the first one missing or out of range. */
    private static String missingField(String operation, Map<String, Object> body) {
        return switch (operation) {
            case "screen" -> firstMissing(body, "applicationId");
            case "open-review-case" -> firstMissing(body, "screeningId", "sourceRef", "sourceProcess", "jurisdiction") != null
                ? firstMissing(body, "screeningId", "sourceRef", "sourceProcess", "jurisdiction")
                : JURISDICTIONS.contains(body.get("jurisdiction")) ? null : "jurisdiction";
            case "record-review-decision" -> REVIEW_DECISIONS.contains(body.get("decision")) ? null : "decision";
            case "reject-application" -> REJECTION_REASONS.contains(body.get("rejectionReason")) ? null : "rejectionReason";
            case "alias-offer" -> firstMissing(body, "applicationId", "accountId");
            case "record-exception-decision" -> EXCEPTION_DECISIONS.contains(body.get("decision")) ? null : "decision";
            case "mark-settled" -> firstMissing(body, "journalRef");
            case "notify" -> firstMissing(body, "subjectRef") != null ? "subjectRef"
                : PURPOSES.contains(body.get("purpose")) ? null : "purpose";
            default -> null;
        };
    }

    private static String firstMissing(Map<String, Object> body, String... fields) {
        for (String field : fields) {
            if (!(body.get(field) instanceof String value) || value.isBlank()) {
                return field;
            }
        }
        return null;
    }

    private boolean consume(Map<String, Integer> counters, String operation) {
        int left = counters.getOrDefault(operation, 0);
        if (left <= 0) {
            return false;
        }
        counters.put(operation, left - 1);
        return true;
    }

    private Reply apply(String operation, String path, String key, Map<String, Object> body) {
        switch (operation) {
            case "kyc-status": {
                Matcher m = KYC.matcher(path);
                m.matches();
                return new Reply(200, Map.of("kycStatus", kycByApplication.getOrDefault(m.group(1), "VERIFIED")));
            }
            case "screen": {
                String application = String.valueOf(body.get("applicationId"));
                return new Reply(201, Map.of("screeningId", "scr-" + application,
                    "decision", screeningByApplication.getOrDefault(application, "PASS")));
            }
            case "open-review-case": {
                String caseId = "case-" + (++sequence);
                applicationByCase.put(caseId, String.valueOf(body.get("sourceRef")));
                return new Reply(201, Map.of("caseId", caseId));
            }
            case "record-review-decision": {
                Matcher m = DECISION.matcher(path);
                m.matches();
                String decision = String.valueOf(body.get("decision"));
                decisionByCase.put(m.group(1), decision);
                // Fail closed: only an explicit CLEAR clears; REJECT, ESCALATE and EXPIRED do not.
                return new Reply(200, Map.of("outcome", decision.equals("CLEAR") ? "CLEARED" : "REJECTED"));
            }
            case "open-account": {
                if (key == null) {
                    return error(400, "IDEMPOTENCY_KEY_REQUIRED");
                }
                Matcher m = OPEN.matcher(path);
                m.matches();
                Map<String, Object> normalized = Map.of("applicationId", m.group(1), "body", body);
                if (openedByKey.containsKey(key)) {
                    if (!openBodyByKey.get(key).equals(normalized)) {
                        return error(409, "IDEMPOTENCY_KEY_REUSED");
                    }
                    return new Reply(200, openedByKey.get(key));
                }
                Map<String, Object> account = Map.of("accountId", "acc-" + (openedByKey.size() + 1));
                openedByKey.put(key, account);
                openBodyByKey.put(key, normalized);
                return new Reply(201, account);
            }
            case "scheme-status": {
                Matcher m = SCHEME_STATUS.matcher(path);
                m.matches();
                List<String> statuses = schemeStatusesByPayment.get(m.group(1));
                String status = statuses == null ? "ACSC" : statuses.size() > 1 ? statuses.remove(0) : statuses.get(0);
                return new Reply(200, Map.of("schemeStatus", status));
            }
            case "capture-hold": {
                if (key == null) {
                    return error(400, "IDEMPOTENCY_KEY_REQUIRED");
                }
                Matcher m = CAPTURE.matcher(path);
                m.matches();
                Map<String, Object> normalized = Map.of("paymentId", m.group(1), "body", body);
                if (capturedByKey.containsKey(key)) {
                    if (!captureBodyByKey.get(key).equals(normalized)) {
                        return error(409, "IDEMPOTENCY_KEY_REUSED");
                    }
                    return new Reply(200, capturedByKey.get(key));
                }
                Map<String, Object> entry = Map.of("journalRef", "jr-" + (capturedByKey.size() + 1));
                capturedByKey.put(key, entry);
                captureBodyByKey.put(key, normalized);
                return new Reply(201, entry);
            }
            case "release-hold": {
                Matcher m = RELEASE.matcher(path);
                m.matches();
                releasesByPayment.merge(m.group(1), 1, Integer::sum);
                return new Reply(200, Map.of());
            }
            case "record-exception-decision": {
                Matcher m = EXCEPTION_DECISION.matcher(path);
                m.matches();
                String decision = String.valueOf(body.get("decision"));
                exceptionDecisionByPayment.put(m.group(1), decision);
                // A test stand-in, not pay's contract: it maps the decision without the check against the gateway's
                // record that pay must make (contracts/expectations/pay.yaml recordExceptionDecision, still requested).
                String outcome = switch (decision) {
                    case "SETTLED" -> "SETTLE";
                    case "REJECTED" -> "RELEASE";
                    default -> "HANDED_BACK";
                };
                return new Reply(200, Map.of("outcome", outcome));
            }
            case "mark-settled":
            case "mark-rejected":
                return new Reply(204, Map.of(), "");
            case "token":
                tokensIssued++;
                return new Reply(200, Map.of("access_token", "token-" + tokensIssued, "expires_in", tokenExpiresIn));
            case "reject-application":
                return new Reply(204, Map.of(), "");
            case "alias-offer":
                return new Reply(200, Map.of("status", "OFFERED"));
            default:
                return new Reply(201, Map.of("notificationRef", "ntf-" + (++sequence)));
        }
    }
}
