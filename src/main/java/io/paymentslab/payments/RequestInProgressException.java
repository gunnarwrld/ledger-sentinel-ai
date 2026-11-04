package io.paymentslab.payments;

/** A request with the same idempotency key is still running; the client should retry shortly. */
public final class RequestInProgressException extends RuntimeException {

    public RequestInProgressException() {
        super("a request with this idempotency key is still in progress", null, false, false);
    }
}
