package io.paymentslab.payments;

import static io.paymentslab.db.Tables.IDEMPOTENCY_KEYS;

import io.paymentslab.db.tables.records.IdempotencyKeysRecord;
import io.paymentslab.platform.TxRunner;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

/**
 * Runs a POST at most once per (caller, operation, idempotency key), with ADR 0007's
 * single-transaction protocol. The key row is the transaction's first write, so a concurrent
 * duplicate waits on it (risk R3, proven by {@code IdempotencyProtocolSpec}). The response is stored
 * in the same commit as the work, so a response lost after commit is recovered by retrying.
 */
public final class Idempotency {

    public record Request(UUID callerId, String operation, String key, byte[] fingerprint) {}

    /** What the work produced: the exact status and body for the client, and the resource it made. */
    public record Completed(int status, String body, UUID resourceId) {}

    public record Result(int status, String body, boolean replayed) {}

    private final TxRunner tx;

    public Idempotency(TxRunner tx) {
        this.tx = tx;
    }

    /**
     * An exception from {@code work} rolls back everything, key included: refusals are never stored,
     * and a retry with the same key runs again.
     */
    public Result execute(Request request, Function<DSLContext, Completed> work) {
        return tx.inTransaction(t -> {
            if (!claim(t, request)) {
                return replay(t, request);
            }
            Completed done = work.apply(t);
            t.update(IDEMPOTENCY_KEYS)
                    .set(IDEMPOTENCY_KEYS.RESPONSE_STATUS, done.status())
                    .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, done.body())
                    .set(IDEMPOTENCY_KEYS.RESOURCE_ID, done.resourceId())
                    .where(key(request))
                    .execute();
            return new Result(done.status(), done.body(), false);
        });
    }

    /** True if this transaction now holds the key; false if a committed row already had it. */
    private static boolean claim(DSLContext t, Request request) {
        try {
            return t.insertInto(IDEMPOTENCY_KEYS)
                    .set(IDEMPOTENCY_KEYS.CALLER_ID, request.callerId())
                    .set(IDEMPOTENCY_KEYS.OPERATION, request.operation())
                    .set(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, request.key())
                    .set(IDEMPOTENCY_KEYS.REQUEST_HASH, request.fingerprint())
                    .onConflict(IDEMPOTENCY_KEYS.CALLER_ID, IDEMPOTENCY_KEYS.OPERATION, IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY)
                    .doNothing()
                    .execute() == 1;
        } catch (DataAccessException e) {
            // Waiting out lock_timeout here means a duplicate is still running: answer 409, don't retry.
            if ("55P03".equals(TxRunner.sqlState(e))) {
                throw new RequestInProgressException();
            }
            throw e;
        }
    }

    /** READ COMMITTED gives this statement a fresh snapshot, so it sees the row the wait was for. */
    private static Result replay(DSLContext t, Request request) {
        IdempotencyKeysRecord stored = t.selectFrom(IDEMPOTENCY_KEYS).where(key(request)).fetchSingle();
        if (!MessageDigest.isEqual(stored.getRequestHash(), request.fingerprint())) {
            throw new IdempotencyKeyReusedException();
        }
        return new Result(stored.getResponseStatus(), stored.getResponseBody(), true);
    }

    private static Condition key(Request request) {
        return IDEMPOTENCY_KEYS.CALLER_ID.eq(request.callerId())
                .and(IDEMPOTENCY_KEYS.OPERATION.eq(request.operation()))
                .and(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(request.key()));
    }
}
