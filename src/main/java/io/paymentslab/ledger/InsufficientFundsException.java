package io.paymentslab.ledger;

import java.util.UUID;

public final class InsufficientFundsException extends PostingRefusedException {
    public InsufficientFundsException(UUID accountId) {
        super(accountId, "has insufficient funds");
    }
}
