package io.paymentslab.support

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import org.jooq.DSLContext

/**
 * Callers with working API keys, inserted with plain SQL and hashed here rather than by the
 * production code, so the API specs don't lean on what they test. Keys are generated per run and
 * are synthetic (ADR 0008).
 */
final class CallerFixtures {

    private static final SecureRandom RANDOM = new SecureRandom()

    private CallerFixtures() {}

    static TestCaller customer(DSLContext db) {
        create(db, "CUSTOMER")
    }

    static TestCaller operator(DSLContext db) {
        create(db, "OPERATOR")
    }

    /** {@code pl_test_} plus 32 random bytes in unpadded base64url. */
    static String newKey() {
        def bytes = new byte[32]
        RANDOM.nextBytes(bytes)
        "pl_test_" + Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    static byte[] sha256(String key) {
        MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))
    }

    private static TestCaller create(DSLContext db, String role) {
        def id = UUID.randomUUID()
        def key = newKey()
        db.execute("INSERT INTO callers (id, name, role, api_key_hash) VALUES (?::uuid, ?, ?, ?)",
                id, "${role.toLowerCase()}-$id".toString(), role, sha256(key))
        new TestCaller(id, role, key)
    }

    static final class TestCaller {
        final UUID id
        final String role
        final String apiKey

        TestCaller(UUID id, String role, String apiKey) {
            this.id = id
            this.role = role
            this.apiKey = apiKey
        }

        String getBearer() {
            "Bearer $apiKey".toString()
        }

        /** Spock prints data variables in test names and failures; keep the key out of reports. */
        @Override
        String toString() {
            "$role $id"
        }
    }
}
