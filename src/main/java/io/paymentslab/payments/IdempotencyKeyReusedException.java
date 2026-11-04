package io.paymentslab.payments;

/** The idempotency key was already used by this caller for a different request. Nothing was changed. */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("idempotency key reused with a different request", null, false, false);
    }
}
