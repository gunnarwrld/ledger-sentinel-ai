package io.paymentslab.payments;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * The canonical fingerprint of a request (DESIGN §6): SHA-256 over the operation and the parsed
 * business fields, each as a 4-byte big-endian length followed by its UTF-8 bytes (an absent field
 * has length -1). Hashing parsed fields instead of raw bytes means whitespace, field order or the
 * case of a UUID don't make a retry look like a different request. The length prefixes stop
 * ("ab", "c") and ("a", "bc") from hashing the same. Single use: {@link #hash()} ends it.
 */
public final class RequestFingerprint {

    private final MessageDigest digest;

    private RequestFingerprint(String operation) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
        add(Objects.requireNonNull(operation, "operation"));
    }

    public static RequestFingerprint of(String operation) {
        return new RequestFingerprint(operation);
    }

    public RequestFingerprint add(String part) {
        if (part == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return this;
        }
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
        return this;
    }

    /** Canonical lower-case text, whatever case the client sent. */
    public RequestFingerprint add(UUID part) {
        return add(part == null ? null : part.toString());
    }

    public RequestFingerprint add(long part) {
        return add(Long.toString(part));
    }

    public byte[] hash() {
        return digest.digest();
    }
}
