package io.paymentslab.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * API key format and hashing (ADR 0008): {@code pl_test_} plus 32 bytes from {@link SecureRandom},
 * in unpadded base64url. Only the SHA-256 hash is stored. A fast hash is enough because the key
 * carries 256 bits of entropy, and being deterministic it turns the lookup into an indexed equality.
 */
public final class ApiKeys {

    private static final String PREFIX = "pl_test_";
    private static final int RANDOM_BYTES = 32;
    /** 32 bytes are 43 characters in unpadded base64url. */
    private static final Pattern WELL_FORMED = Pattern.compile(Pattern.quote(PREFIX) + "[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A shape check that runs before any lookup, so junk never costs a hash or a query. */
    public static boolean isWellFormed(String key) {
        return key != null && WELL_FORMED.matcher(key).matches();
    }

    /** SHA-256 of the whole key, prefix included. */
    public static byte[] hash(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
