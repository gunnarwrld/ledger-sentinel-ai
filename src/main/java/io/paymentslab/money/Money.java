package io.paymentslab.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount in integer minor units (cents for EUR) plus its currency.
 *
 * <p>Arithmetic is checked: a result that does not fit in a {@code long} throws
 * {@link ArithmeticException} instead of silently wrapping. Negative amounts are allowed because
 * SYSTEM account balances can be negative; transfer amounts are restricted by {@link TransferAmount}.
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    /** {@code Long.MIN_VALUE} has no positive counterpart, so negating it throws. */
    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(
                    "cannot combine " + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
    }
}
