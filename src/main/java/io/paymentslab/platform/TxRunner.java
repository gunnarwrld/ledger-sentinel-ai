package io.paymentslab.platform;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

/**
 * Runs a unit of work in one READ COMMITTED transaction with a 2 s {@code lock_timeout}, and retries
 * the whole unit when PostgreSQL reports a transient conflict: deadlock (40P01), serialization
 * failure (40001) or lock timeout (55P03). Retrying is safe because nothing has committed. At most
 * three attempts with 10 ms · 2ⁿ backoff (±50 % jitter); then {@link ContentionException}.
 *
 * <p>A step that gives 55P03 another meaning must translate it before it reaches this class: a lock
 * timeout on an idempotency key means a duplicate is in flight, not contention (DESIGN §6).
 */
public final class TxRunner {

    static final int MAX_ATTEMPTS = 3;
    private static final Set<String> TRANSIENT = Set.of("40P01", "40001", "55P03");

    private final DSLContext db;
    private final LongConsumer pause;

    public TxRunner(DSLContext db) {
        this(db, TxRunner::sleep);
    }

    /** {@code pause} receives each backoff in milliseconds; specs record it instead of sleeping. */
    public TxRunner(DSLContext db, LongConsumer pause) {
        this.db = db;
        this.pause = pause;
    }

    public <T> T inTransaction(Function<DSLContext, T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return db.transactionResult(configuration -> {
                    DSLContext tx = DSL.using(configuration);
                    tx.execute("SET LOCAL lock_timeout = '2s'");
                    return work.apply(tx);
                });
            } catch (DataAccessException e) {
                if (!TRANSIENT.contains(sqlState(e))) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new ContentionException(e);
                }
                pause.accept(backoffMillis(attempt));
            }
        }
    }

    /** The SQLSTATE of the first {@link SQLException} in the cause chain, or null. */
    public static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** 10 ms after the first failure, 20 ms after the second, each ±50 % so retries don't collide again. */
    private static long backoffMillis(int attempt) {
        long base = 10L << (attempt - 1);
        return base / 2 + ThreadLocalRandom.current().nextLong(base + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }
}
