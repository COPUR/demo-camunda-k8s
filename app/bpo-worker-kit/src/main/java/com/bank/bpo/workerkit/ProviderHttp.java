package com.bank.bpo.workerkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON over HTTPS to one domain service, with its bearer token, the idempotency header and an interaction id.
 * A deployed worker only ever gets {@link #https}; {@link #loopback} exists for the in-process simulator.
 * A 2xx answer that is not a JSON object is a contract fault, never an empty success.
 */
public final class ProviderHttp {

    public static final String IDEMPOTENCY_HEADER = "x-idempotency-key";
    public static final String INTERACTION_HEADER = "x-fapi-interaction-id";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final URI baseUrl;
    private final Supplier<String> token;
    private final Duration timeout;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private ProviderHttp(URI baseUrl, Supplier<String> token, Duration timeout) {
        if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null || baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("a provider base URL is scheme, host, port and path only");
        }
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeout = timeout;
    }

    /** A provider reached over TLS. Anything but an https URL with a host refuses to start. */
    public static ProviderHttp https(URI baseUrl, Supplier<String> token, Duration timeout) {
        if (!"https".equals(baseUrl.getScheme()) || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("a provider base URL must be https");
        }
        return new ProviderHttp(baseUrl, token, timeout);
    }

    /** The in-process simulator: plain http, and only to the loopback address, so it can never reach a real service. */
    public static ProviderHttp loopback(URI baseUrl, Supplier<String> token, Duration timeout) {
        if (!"http".equals(baseUrl.getScheme()) || !isLoopback(baseUrl.getHost())) {
            throw new IllegalArgumentException("a simulator base URL must be http on the loopback address");
        }
        return new ProviderHttp(baseUrl, token, timeout);
    }

    static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
    }

    public Map<String, Object> get(String path) {
        return send(request(path).GET().build());
    }

    public Map<String, Object> post(String path, Map<String, ?> body, String idempotencyKey) {
        HttpRequest.Builder request = request(path).header("content-type", "application/json");
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }

    private HttpRequest.Builder request(String path) {
        // Adapters build paths from Ids-checked segments; this is the second line: no dot segment, escape or
        // empty segment, and the result stays under the base URL.
        if (!path.startsWith("/") || path.contains("..") || path.contains("%") || path.contains("//")
            || path.contains("?") || path.contains("#") || path.contains("\\")) {
            throw new ProviderException(ProviderException.Kind.CONTRACT, "INVALID_PATH", "path is not a plain resource path", null);
        }
        URI target = baseUrl.resolve(path);
        if (!target.getHost().equals(baseUrl.getHost()) || target.getPort() != baseUrl.getPort()) {
            throw new ProviderException(ProviderException.Kind.CONTRACT, "INVALID_PATH", "path leaves the provider", null);
        }
        return HttpRequest.newBuilder(target)
            .timeout(timeout)
            .header("authorization", "Bearer " + token.get())
            .header(INTERACTION_HEADER, Interaction.current())
            .header("accept", "application/json");
    }

    private Map<String, Object> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.TRANSIENT, "UNREACHABLE", request.uri().getPath() + " not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.TRANSIENT, "INTERRUPTED", "interrupted", e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parseSuccess(request, response.body());
        }
        Map<String, Object> body = parseError(response.body());
        String code = String.valueOf(body.getOrDefault("code", "HTTP_" + status));
        String message = request.uri().getPath() + " answered " + status + " " + code;
        if (status == 409 && "IDEMPOTENCY_KEY_REUSED".equals(code)) {
            throw new ProviderException(ProviderException.Kind.KEY_REUSED, code, message, null);
        }
        if (status == 422) {
            throw new ProviderException(ProviderException.Kind.BUSINESS_REJECTION, code, message, null);
        }
        if (status == 409 || status == 429 || status >= 500) {
            throw new ProviderException(ProviderException.Kind.TRANSIENT, code, message, null);
        }
        throw new ProviderException(ProviderException.Kind.CONTRACT, code, message, null);
    }

    /** 204 or an empty body is an answer without fields; anything else must be a JSON object. */
    private Map<String, Object> parseSuccess(HttpRequest request, String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(body, MAP);
            if (parsed == null) {
                throw new IllegalStateException("null body");
            }
            return parsed;
        } catch (RuntimeException e) {
            throw new ProviderException(ProviderException.Kind.CONTRACT, "UNREADABLE_ANSWER",
                request.uri().getPath() + " answered 2xx without a JSON object", null);
        }
    }

    /** An error body only supplies the provider's code; an unreadable one falls back to the status. */
    private Map<String, Object> parseError(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(body, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
