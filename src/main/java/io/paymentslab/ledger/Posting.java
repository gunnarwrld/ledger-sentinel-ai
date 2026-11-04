package io.paymentslab.ledger;

import io.paymentslab.money.Money;
import java.util.Objects;
import java.util.UUID;

/** One leg of a journal entry. Signed (ADR 0005): positive is a credit, negative is a debit. */
public record Posting(UUID accountId, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }
}
