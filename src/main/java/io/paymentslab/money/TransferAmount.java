package io.paymentslab.money;

import java.util.Currency;
import java.util.Objects;

/** A valid amount for a single transfer: a supported currency and 1 to {@link #MAX_MINOR} minor units. */
public record TransferAmount(Money money) {

    /** 1,000,000.00 EUR. Also keeps JSON numbers far below 2^53 (DESIGN A6). */
    public static final long MAX_MINOR = 100_000_000L;

    public TransferAmount {
        Objects.requireNonNull(money, "money");
        SupportedCurrencies.require(money.currency());
        if (money.minor() < 1 || money.minor() > MAX_MINOR) {
            throw new InvalidAmountException("amount must be between 1 and " + MAX_MINOR + " minor units");
        }
    }

    public static TransferAmount of(long minor, Currency currency) {
        return new TransferAmount(Money.of(minor, currency));
    }
}
