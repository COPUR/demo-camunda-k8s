package com.bank.bpo.exceptions.adapter.out.camunda;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.camunda.client.CamundaClient;
import io.camunda.client.impl.NoopCredentialsProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * The engine's REST API on the loopback address, answering from a script: the real client talks to it, so the test
 * covers the adapter's requests as they go over the wire, not a mock's idea of them.
 */
final class FakeEngine implements AutoCloseable {

    record Call(String method, String path, String body) {
    }

    record Answer(int status, String body, long delayMillis) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PROCESS_INSTANCES = "POST /v2/process-instances";
    static final String PROCESS_INSTANCE_SEARCH = "POST /v2/process-instances/search";
    static final String USER_TASK_SEARCH = "POST /v2/user-tasks/search";
    static final String VARIABLE_SEARCH = "POST /v2/variables/search";
    static final String ELEMENT_INSTANCE_SEARCH = "POST /v2/element-instances/search";

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Answer>> script = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakeEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Queues an answer for a request line; the last answer queued for it is repeated. */
    FakeEngine answer(String requestLine, int status, Object body) {
        return answer(requestLine, status, body, 0);
    }

    FakeEngine answer(String requestLine, int status, Object body, long delayMillis) {
        String json = body == null ? "" : body instanceof String text ? text : JSON.writeValueAsString(body);
        script.computeIfAbsent(requestLine, k -> new ArrayDeque<>()).add(new Answer(status, json, delayMillis));
        return this;
    }

    List<Call> calls(String requestLine) {
        return calls.stream().filter(call -> (call.method() + " " + call.path()).equals(requestLine)).toList();
    }

    CamundaClient client() {
        URI address = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return CamundaClient.newClientBuilder()
            .restAddress(address)
            .grpcAddress(address)
            .preferRestOverGrpc(true)
            .credentialsProvider(new NoopCredentialsProvider())
            .defaultRequestTimeout(Duration.ofSeconds(5))
            .build();
    }

    void stop() {
        server.stop(0);
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String requestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        Deque<Answer> answers = script.get(requestLine);
        Answer answer = answers == null || answers.isEmpty()
            ? new Answer(404, JSON.writeValueAsString(problem(404, "no scripted answer for " + requestLine)), 0)
            : answers.size() > 1 ? answers.poll() : answers.peek();
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", answer.status() >= 400 ? "application/problem+json" : "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(answer.status(), -1);
        } else {
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    // ---- engine responses as the 8.10 REST API shapes them

    static Map<String, Object> problem(int status, String detail) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status == 409 ? "INVALID_STATE" : status == 404 ? "NOT_FOUND" : "ERROR");
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", "/v2");
        return problem;
    }

    static Map<String, Object> page(List<?> items, String endCursor) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("totalItems", items.size());
        page.put("hasMoreTotalItems", false);
        page.put("startCursor", endCursor);
        page.put("endCursor", endCursor);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("page", page);
        return response;
    }

    static Map<String, Object> created(long processInstanceKey, String businessId) {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("processDefinitionId", "pay-instant-exception");
        created.put("processDefinitionVersion", 3);
        created.put("tenantId", "<default>");
        created.put("variables", Map.of());
        created.put("processDefinitionKey", "2251799813685100");
        created.put("processInstanceKey", Long.toString(processInstanceKey));
        created.put("tags", List.of());
        created.put("businessId", businessId);
        return created;
    }

    static Map<String, Object> instance(long key, String state, boolean incident) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("processDefinitionId", "pay-instant-exception");
        instance.put("processDefinitionName", "Instant payment exception");
        instance.put("processDefinitionVersion", 3);
        instance.put("processDefinitionVersionTag", "0.1.0");
        instance.put("startDate", "2026-11-02T10:00:00.000Z");
        instance.put("endDate", state.equals("ACTIVE") ? null : "2026-11-02T11:00:00.000Z");
        instance.put("state", state);
        instance.put("hasIncident", incident);
        instance.put("tenantId", "<default>");
        instance.put("processInstanceKey", Long.toString(key));
        instance.put("processDefinitionKey", "2251799813685100");
        instance.put("rootProcessInstanceKey", Long.toString(key));
        instance.put("tags", List.of());
        return instance;
    }

    static Map<String, Object> userTask(long taskKey, long instanceKey, String elementId, String group, String assignee) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", "Check the scheme record");
        task.put("state", "CREATED");
        task.put("assignee", assignee);
        task.put("elementId", elementId);
        task.put("candidateGroups", group == null ? List.of() : List.of(group));
        task.put("candidateUsers", List.of());
        task.put("processDefinitionId", "pay-instant-exception");
        task.put("creationDate", "2026-11-02T10:05:00.000Z");
        task.put("dueDate", "2026-11-02T10:35:00.000Z");
        task.put("tenantId", "<default>");
        task.put("processDefinitionVersion", 3);
        task.put("customHeaders", Map.of());
        task.put("priority", 50);
        task.put("userTaskKey", Long.toString(taskKey));
        task.put("elementInstanceKey", Long.toString(taskKey - 1));
        task.put("processName", "Instant payment exception");
        task.put("processDefinitionKey", "2251799813685100");
        task.put("processInstanceKey", Long.toString(instanceKey));
        task.put("rootProcessInstanceKey", Long.toString(instanceKey));
        task.put("tags", List.of());
        return task;
    }

    /** A task that is no longer open, as the search shows it once its end (or the step towards it) is exported. */
    static Map<String, Object> endedUserTask(long taskKey, long instanceKey, String elementId, String state) {
        Map<String, Object> task = userTask(taskKey, instanceKey, elementId, null, null);
        task.put("state", state);
        task.put("completionDate", "2026-11-02T10:20:00.000Z");
        return task;
    }

    static Map<String, Object> variable(long instanceKey, long scopeKey, String name, String value) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("value", JSON.writeValueAsString(value));
        variable.put("isTruncated", false);
        variable.put("tenantId", "<default>");
        variable.put("variableKey", Long.toString(scopeKey + name.hashCode() & 0xffff));
        variable.put("scopeKey", Long.toString(scopeKey));
        variable.put("processInstanceKey", Long.toString(instanceKey));
        variable.put("rootProcessInstanceKey", Long.toString(instanceKey));
        return variable;
    }

    static List<Map<String, Object>> variables(long instanceKey, Map<String, String> values) {
        return values.entrySet().stream().map(entry -> variable(instanceKey, instanceKey, entry.getKey(), entry.getValue())).toList();
    }

    static Map<String, Object> elementInstance(long instanceKey, String elementId) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("processDefinitionId", "pay-instant-exception");
        element.put("startDate", "2026-11-02T10:00:01.000Z");
        element.put("elementId", elementId);
        element.put("elementName", "Await scheme status");
        element.put("type", "CALL_ACTIVITY");
        element.put("state", "ACTIVE");
        element.put("hasIncident", false);
        element.put("tenantId", "<default>");
        element.put("elementInstanceKey", Long.toString(instanceKey + 1));
        element.put("processInstanceKey", Long.toString(instanceKey));
        element.put("processDefinitionKey", "2251799813685100");
        element.put("rootProcessInstanceKey", Long.toString(instanceKey));
        return element;
    }
}
