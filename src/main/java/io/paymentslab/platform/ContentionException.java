package io.paymentslab.platform;

/** Retries ran out on a transient conflict (deadlock, serialization failure, lock timeout). Nothing was committed. */
public final class ContentionException extends RuntimeException {

    public ContentionException(Throwable lastFailure) {
        super("transaction retries exhausted", lastFailure);
    }
}
