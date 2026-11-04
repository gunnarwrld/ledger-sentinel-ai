package io.paymentslab.api;

import static io.paymentslab.db.Tables.CALLERS;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;

/**
 * Callers and their API keys (ADR 0008). The database keeps only SHA-256 hashes; a key exists in
 * plain text only inside {@link Created}, long enough to be shown once.
 */
public final class Callers {

    /** Letters, digits, '.', '_' and '-': nothing that can break a log line or a terminal. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** A new caller and its API key. {@link #toString()} redacts the key. */
    public record Created(Caller caller, String apiKey) {
        @Override
        public String toString() {
            return "Created[caller=" + caller + ", apiKey=***]";
        }
    }

    /** @throws IllegalArgumentException if the name is malformed or already taken */
    public Created create(DSLContext db, String name, CallerRole role) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must be 1 to 64 letters, digits, '.', '_' or '-'");
        }
        Objects.requireNonNull(role, "role");
        UUID id = UUID.randomUUID();
        String key = ApiKeys.generate();
        // ON CONFLICT instead of check-then-insert: two concurrent creates can't both win.
        int inserted = db.insertInto(CALLERS)
                .set(CALLERS.ID, id)
                .set(CALLERS.NAME, name)
                .set(CALLERS.ROLE, role.name())
                .set(CALLERS.API_KEY_HASH, ApiKeys.hash(key))
                .onConflict(CALLERS.NAME).doNothing()
                .execute();
        if (inserted == 0) {
            throw new IllegalArgumentException("a caller named " + name + " already exists");
        }
        return new Created(new Caller(id, name, role), key);
    }

    /** The caller holding this key. A malformed key is rejected without touching the database. */
    public Optional<Caller> findByApiKey(DSLContext db, String key) {
        if (!ApiKeys.isWellFormed(key)) {
            return Optional.empty();
        }
        return db.select(CALLERS.ID, CALLERS.NAME, CALLERS.ROLE)
                .from(CALLERS)
                .where(CALLERS.API_KEY_HASH.eq(ApiKeys.hash(key)))
                .fetchOptional(row -> new Caller(row.value1(), row.value2(), CallerRole.valueOf(row.value3())));
    }
}
