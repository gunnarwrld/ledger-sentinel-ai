package io.paymentslab.payments;

/** The payment's current status doesn't allow the requested change (DESIGN §4.3). Nothing was written. */
public final class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("a " + from + " payment can't become " + to, null, false, false);
    }
}
