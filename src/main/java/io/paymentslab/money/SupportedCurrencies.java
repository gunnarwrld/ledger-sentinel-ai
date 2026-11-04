package io.paymentslab.money;

import java.util.Currency;
import java.util.Set;

/**
 * The currencies this system accepts. {@link Money} can hold any ISO currency; this is the policy
 * applied at the edges. Single-currency scope (DESIGN A1): the {@code accounts.currency} CHECK
 * constraint must list the same codes.
 */
public final class SupportedCurrencies {

    private static final Set<Currency> SUPPORTED = Set.of(Currency.getInstance("EUR"));

    private SupportedCurrencies() {}

    /** Exact, case-sensitive match on the ISO code: "eur" or " EUR" are rejected, not normalized. */
    public static Currency parse(String code) {
        if (code == null) {
            throw new UnsupportedCurrencyException("currency is required");
        }
        return SUPPORTED.stream()
                .filter(currency -> currency.getCurrencyCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new UnsupportedCurrencyException("unsupported currency"));
    }

    public static Currency require(Currency currency) {
        if (currency == null || !SUPPORTED.contains(currency)) {
            throw new UnsupportedCurrencyException("unsupported currency: " + currency);
        }
        return currency;
    }
}
