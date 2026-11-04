package io.paymentslab.money;

public final class UnsupportedCurrencyException extends IllegalArgumentException {
    public UnsupportedCurrencyException(String message) {
        super(message);
    }
}
