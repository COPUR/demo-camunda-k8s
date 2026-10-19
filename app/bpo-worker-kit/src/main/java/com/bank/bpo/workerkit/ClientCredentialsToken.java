package com.bank.bpo.workerkit;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Keycloak client-credentials token for one worker family (svc-bpo-worker-family), cached until shortly
 * before it expires. The scope names the audiences the family may call (07 section 2.1).
 */
public final class ClientCredentialsToken implements Supplier<String> {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    private final URI tokenUri;
    private final String form;
    private final Clock clock;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String cached;
    private Instant expiresAt = Instant.MIN;

    public ClientCredentialsToken(URI tokenUri, String clientId, String clientCredential, String scope, Clock clock) {
        if (!"https".equals(tokenUri.getScheme()) && !("http".equals(tokenUri.getScheme()) && ProviderHttp.isLoopback(tokenUri.getHost()))) {
            throw new IllegalArgumentException("the token endpoint must be https (plain http only on loopback, for tests)");
        }
        for (String[] required : new String[][] {{"client id", clientId}, {"client credential", clientCredential}, {"scope", scope}}) {
            if (required[1] == null || required[1].isBlank()) {
                throw new IllegalArgumentException(required[0] + " is required");
            }
        }
        this.tokenUri = tokenUri;
        this.form = "grant_type=client_credentials"
            + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientCredential) + "&scope=" + enc(scope);
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (cached == null || !clock.instant().isBefore(expiresAt.minus(SAFETY_MARGIN))) {
            refresh();
        }
        return cached;
    }

    private void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                // 400, 401 and 403 mean the client, its credential or its scope is wrong: retrying cannot help.
                ProviderException.Kind kind = status == 429 || status >= 500 ? ProviderException.Kind.TRANSIENT : ProviderException.Kind.CONTRACT;
                throw new ProviderException(kind, "TOKEN_HTTP_" + status, "token endpoint answered " + status, null);
            }
            Map<String, Object> body;
            try {
                body = json.readValue(response.body(), MAP);
            } catch (RuntimeException e) {
                body = null;
            }
            if (body == null || !(body.get("access_token") instanceof String access) || access.isBlank()
                || !(body.get("expires_in") instanceof Number lifetime) || lifetime.longValue() <= 0) {
                throw new ProviderException(ProviderException.Kind.CONTRACT, "TOKEN_UNREADABLE", "token endpoint answered without a token", null);
            }
            cached = access;
            expiresAt = clock.instant().plusSeconds(lifetime.longValue());
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.TRANSIENT, "TOKEN_UNREACHABLE", "token endpoint not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.TRANSIENT, "INTERRUPTED", "interrupted", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
