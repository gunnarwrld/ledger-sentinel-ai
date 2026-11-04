package io.paymentslab.money;

public final class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
