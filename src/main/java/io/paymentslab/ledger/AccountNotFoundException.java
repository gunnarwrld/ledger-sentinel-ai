package io.paymentslab.ledger;

import java.util.UUID;

public final class AccountNotFoundException extends PostingRefusedException {
    public AccountNotFoundException(UUID accountId) {
        super(accountId, "does not exist");
    }
}
