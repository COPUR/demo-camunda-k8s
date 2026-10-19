package com.bank.bpo.demo;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a bearer token the way a provider does: an RS256 signature by a key from the issuer's JWKS, the issuer, the
 * expiry, the provider's audience and the caller ({@code azp}) on the allow-list. A bad or expired token is
 * {@link Outcome#UNAUTHENTICATED}; a valid token for another audience or caller is {@link Outcome#FORBIDDEN}.
 */
final class JwtVerifier {

    enum Outcome { OK, UNAUTHENTICATED, FORBIDDEN }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final String issuer;
    private final String audience;
    private final Set<String> allowedCallers;
    private final Function<String, PublicKey> keys;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    JwtVerifier(String issuer, String audience, Set<String> allowedCallers, Function<String, PublicKey> keys, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.allowedCallers = Set.copyOf(allowedCallers);
        this.keys = keys;
        this.clock = clock;
    }

    Outcome verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Outcome.UNAUTHENTICATED;
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) {
            return Outcome.UNAUTHENTICATED;
        }
        try {
            Map<String, Object> header = decode(parts[0]);
            if (!"RS256".equals(header.get("alg")) || !(header.get("kid") instanceof String kid)) {
                return Outcome.UNAUTHENTICATED;
            }
            PublicKey key = keys.apply(kid);
            if (key == null || !signed(key, parts[0] + "." + parts[1], Base64.getUrlDecoder().decode(parts[2]))) {
                return Outcome.UNAUTHENTICATED;
            }
            Map<String, Object> claims = decode(parts[1]);
            if (!issuer.equals(claims.get("iss")) || !(claims.get("exp") instanceof Number exp)
                    || clock.instant().minus(SKEW).getEpochSecond() >= exp.longValue()) {
                return Outcome.UNAUTHENTICATED;
            }
            return audiences(claims.get("aud")).contains(audience) && allowedCallers.contains(claims.get("azp"))
                ? Outcome.OK : Outcome.FORBIDDEN;
        } catch (RuntimeException | GeneralSecurityException e) {
            return Outcome.UNAUTHENTICATED;
        }
    }

    private Map<String, Object> decode(String part) {
        return json.readValue(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8), MAP);
    }

    private static boolean signed(PublicKey key, String input, byte[] signature) throws GeneralSecurityException {
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(key);
        rsa.update(input.getBytes(StandardCharsets.US_ASCII));
        return rsa.verify(signature);
    }

    private static List<?> audiences(Object aud) {
        return aud instanceof List<?> list ? list : aud == null ? List.of() : List.of(aud);
    }

    /** RSA signing keys of a JWKS endpoint, fetched again when a token names a key id not seen yet. */
    static Function<String, PublicKey> jwks(URI jwksUri) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JsonMapper json = JsonMapper.builder().build();
        Map<String, PublicKey> cache = new HashMap<>();
        return kid -> {
            synchronized (cache) {
                if (!cache.containsKey(kid)) {
                    cache.clear();
                    cache.putAll(fetch(client, json, jwksUri));
                }
                return cache.get(kid);
            }
        };
    }

    private static Map<String, PublicKey> fetch(HttpClient client, JsonMapper json, URI jwksUri) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of();
            }
            Map<String, PublicKey> keys = new HashMap<>();
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            for (Object entry : (List<?>) json.readValue(response.body(), MAP).get("keys")) {
                Map<?, ?> jwk = (Map<?, ?>) entry;
                if ("RSA".equals(jwk.get("kty")) && !"enc".equals(jwk.get("use")) && jwk.get("kid") instanceof String kid) {
                    keys.put(kid, rsa.generatePublic(new RSAPublicKeySpec(unsigned((String) jwk.get("n")), unsigned((String) jwk.get("e")))));
                }
            }
            return keys;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static BigInteger unsigned(String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }
}
