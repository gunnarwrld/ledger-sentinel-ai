package io.paymentslab.ledger;

/** The entry itself is malformed (not balanced, mixed currencies, ...): a programming error, not a business decline. */
public final class InvalidEntryException extends IllegalArgumentException {
    public InvalidEntryException(String message) {
        super(message);
    }
}
