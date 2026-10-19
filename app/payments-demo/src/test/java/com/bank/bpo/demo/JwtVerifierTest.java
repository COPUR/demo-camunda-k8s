package com.bank.bpo.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JwtVerifierTest {

    static final String ISSUER = "http://localhost:18080/auth/realms/camunda-platform";
    static final Instant NOW = Instant.parse("2026-10-18T20:00:00Z");
    static final KeyPair KEYS = keys();
    static final KeyPair OTHER_KEYS = keys();

    final JwtVerifier verifier = new JwtVerifier(ISSUER, "svc-psg-tr", Set.of("svc-bpo-worker-payments"),
        kid -> "k1".equals(kid) ? KEYS.getPublic() : null, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aTokenSignedByTheIssuerForThisAudienceAndCallerIsAccepted() {
        assertThat(verifier.verify(bearer(KEYS.getPrivate(), "RS256", claims(List.of("svc-psg-tr", "orchestration-api"), "svc-bpo-worker-payments", 300))))
            .isEqualTo(JwtVerifier.Outcome.OK);
    }

    @Test
    void aValidTokenForAnotherAudienceOrCallerIsForbidden() {
        assertThat(verifier.verify(bearer(KEYS.getPrivate(), "RS256", claims(List.of("svc-acc-accounts-ledger"), "svc-bpo-worker-payments", 300))))
            .isEqualTo(JwtVerifier.Outcome.FORBIDDEN);
        assertThat(verifier.verify(bearer(KEYS.getPrivate(), "RS256", claims(List.of("svc-psg-tr"), "demo-loader", 300))))
            .isEqualTo(JwtVerifier.Outcome.FORBIDDEN);
    }

    @Test
    void aMissingExpiredForeignOrUnsignedTokenIsUnauthenticated() {
        assertThat(verifier.verify(null)).isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
        assertThat(verifier.verify("Basic abc")).isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
        assertThat(verifier.verify(bearer(KEYS.getPrivate(), "RS256", claims(List.of("svc-psg-tr"), "svc-bpo-worker-payments", -60))))
            .isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
        assertThat(verifier.verify(bearer(OTHER_KEYS.getPrivate(), "RS256", claims(List.of("svc-psg-tr"), "svc-bpo-worker-payments", 300))))
            .isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
        assertThat(verifier.verify(bearer(null, "none", claims(List.of("svc-psg-tr"), "svc-bpo-worker-payments", 300))))
            .isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
        Map<String, Object> otherIssuer = new java.util.HashMap<>(claims(List.of("svc-psg-tr"), "svc-bpo-worker-payments", 300));
        otherIssuer.put("iss", "https://elsewhere.example/realms/x");
        assertThat(verifier.verify(bearer(KEYS.getPrivate(), "RS256", otherIssuer))).isEqualTo(JwtVerifier.Outcome.UNAUTHENTICATED);
    }

    static Map<String, Object> claims(Object aud, String azp, long expiresInSeconds) {
        return Map.of("iss", ISSUER, "aud", aud, "azp", azp, "exp", NOW.getEpochSecond() + expiresInSeconds);
    }

    static String bearer(PrivateKey key, String alg, Map<String, Object> claims) {
        try {
            JsonMapper json = JsonMapper.builder().build();
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String input = b64.encodeToString(json.writeValueAsBytes(Map.of("alg", alg, "kid", "k1")))
                + "." + b64.encodeToString(json.writeValueAsBytes(claims));
            String signature = "";
            if (key != null) {
                Signature rsa = Signature.getInstance("SHA256withRSA");
                rsa.initSign(key);
                rsa.update(input.getBytes(StandardCharsets.US_ASCII));
                signature = b64.encodeToString(rsa.sign());
            }
            return "Bearer " + input + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
