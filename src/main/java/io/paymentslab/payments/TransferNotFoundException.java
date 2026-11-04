package io.paymentslab.payments;

/** No transfer with that id. */
public final class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("no such transfer", null, false, false);
    }
}
