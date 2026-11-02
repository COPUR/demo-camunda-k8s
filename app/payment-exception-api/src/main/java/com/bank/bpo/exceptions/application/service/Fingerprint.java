package com.bank.bpo.exceptions.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A SHA-256 digest of a request's meaningful parts: what an idempotency record compares a retry against. */
final class Fingerprint {

    private Fingerprint() {
    }

    static String of(String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK provides SHA-256", e);
        }
        for (String part : parts) {
            byte[] bytes = Objects.requireNonNull(part, "fingerprint part").getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
